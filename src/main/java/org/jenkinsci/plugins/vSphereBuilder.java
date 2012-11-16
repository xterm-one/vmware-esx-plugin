package org.jenkinsci.plugins;

import com.vmware.vim25.*;
import com.vmware.vim25.mo.*;
import com.vmware.vim25.mo.util.MorUtil;
import hudson.Extension;
import hudson.*;
import hudson.Util;
import hudson.model.*;
import hudson.remoting.Channel;
import hudson.slaves.*;
import hudson.tasks.BuildStep;
import hudson.tasks.BuildWrapper;
import hudson.tasks.Builder;
import hudson.util.*;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class vSphereBuilder extends BuildWrapper {
	private static final Logger logger = Logger.getLogger(vSphereBuilder.class.getName());
	private List<Builder> preBuildSteps = new ArrayList<Builder>();
	private List<Builder> postBuildSteps = new ArrayList<Builder>();
	
	public List<Builder> getPreBuildSteps() {
		return preBuildSteps;
	}
	
	public void setPreBuildSteps(List<Builder> preBuildSteps) {
		this.preBuildSteps = preBuildSteps;
	}
	
	public List<Builder> getPostBuildSteps() {
		return postBuildSteps;
	}
	
	public void setpostBuildSteps(List<Builder> postBuildSteps) {
		this.postBuildSteps = postBuildSteps;
	}

	public enum RunOrder {
		Before("Before Build"),
		After("After Build");
		
		private final String value;
		
		RunOrder(String value) {
			this.value = value;
		}
		
		@Override
		public String toString() {
			return value;
		}
	}
	
	private final String vsHost;
	private final String vmName;
	private final String snapname;
	private final String username;
	private final String password;
	private final Integer waitpause;
	private final Boolean waitfortools;
	private final RunOrder runOrder;
	
	private final ArrayList<BuildStep> buildSteps;

	// Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
	@DataBoundConstructor
	public vSphereBuilder(
		String vsHost,
		String vmName,
		String username,
		String password,
		String snapname,
		Integer waitpause,
		Boolean waitfortools,
		RunOrder runOrder,
		ArrayList<BuildStep> buildstep
	) {
		this.vsHost = vsHost;
		this.vmName = vmName;
		this.username = username;
		this.password = Scrambler.scramble(Util.fixEmptyAndTrim(password));
		this.snapname = snapname;
		this.waitpause = waitpause;
		this.waitfortools = waitfortools;
		this.runOrder = runOrder;
		this.buildSteps = buildstep;
	}
	
	@Override 
	public void preCheckout(
			AbstractBuild build,
			Launcher launcher,
			BuildListener listener
			) throws IOException,
			InterruptedException {
		
		PrintStream log = listener.getLogger();
		log.println("Revert to snapshot if before build");
		if (getRunOrder().contains("Before"))
			revertToSnapshot(build, launcher, listener);
	}
	
	@Override
	public Environment setUp(
			AbstractBuild build,
			final Launcher launcher,
            BuildListener listener
			) throws IOException,
			InterruptedException {
		
		if (!executeBuildSteps(preBuildSteps, build, launcher, listener)) {
			throw new IOException("Could not execute pre-build steps");
		}
		return new Environment() {
			@Override
			public boolean tearDown(
				AbstractBuild build,
				BuildListener listener
				) throws IOException,
				InterruptedException {
					PrintStream log = listener.getLogger();
					boolean result = executeBuildSteps(postBuildSteps, build, launcher, listener);
					log.println("Revert to snapshot if after build...");
					if (getRunOrder().contains("After"))
						revertToSnapshot(build, launcher, listener);
					return result;
			 }
		};
	}
	
	private boolean executeBuildSteps(
			List<Builder> buildSteps,
			AbstractBuild build,
			Launcher launcher,
			BuildListener listener
			) throws IOException,
			InterruptedException {
		
		boolean shouldContinue = true;
		
		for (BuildStep buildStep : buildSteps) {
			if (!shouldContinue) {
				break;
			}
			shouldContinue = buildStep.prebuild(build, listener);
		}
		
		for (BuildStep buildStep : buildSteps) {
			if (!shouldContinue) {
				break;
			}
			shouldContinue = buildStep.perform(build, launcher, listener);
		}
		return shouldContinue;
	}
	
	public void revertToSnapshot(AbstractBuild build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
		final SlaveComputer vsSlave = (SlaveComputer) Computer.currentComputer();
		
		PrintStream log = listener.getLogger();

		log.println("Disconnect the target VM...");
		vsSlave.disconnect(
			new OfflineCause.ByCLI(
				"VM is offline while it is being reverted"
			)
		);

		final ServiceInstance si;
		final VirtualMachine vm;
		try {
			si = new ServiceInstance(new URL(vsHost + "/sdk"), username, getPassword(), true);
			Folder rootFolder = si.getRootFolder();
			vm = (VirtualMachine) new InventoryNavigator(rootFolder).searchManagedEntity("VirtualMachine", vmName);
		} catch(Exception e) {
			throw new RuntimeException(e);
		}

		// Revert to a snapshot - always - if one is specified.
		VirtualMachineSnapshotInfo mysnap = vm.getSnapshot();
		VirtualMachineSnapshotTree[] children = mysnap.getRootSnapshotList();
		for(VirtualMachineSnapshotTree child : children)
			if(child.getName().equals(snapname)) {
				ManagedObjectReference morSnapshot = child.getSnapshot();
				ManagedObject moSnapshot = MorUtil.createExactManagedObject(si.getServerConnection(), morSnapshot);
				VirtualMachineSnapshot snap = (VirtualMachineSnapshot) moSnapshot;
				
				log.println("Revert to snapshot " + snapname);
				Task revert = snap.revertToSnapshot_Task(null);
				if(!revert.waitForTask().equals(Task.SUCCESS))
					throw new IOException("Error while reverting to virtual machine snapshot");
				
				log.println("Power on target VM...");
				Task work = vm.powerOnVM_Task(null);
				if(!work.waitForTask().equals(Task.SUCCESS))
					throw new IOException("Unable to power on VM");
				
				log.println("Pause for vmware tools...");
				if(waitfortools) {
					Calendar target = Calendar.getInstance();
					target.add(Calendar.SECOND, 120);
					
					synchronized(this) {
						while(Calendar.getInstance().before(target)) {
							VirtualMachineToolsStatus status = vm.getGuest().toolsStatus;
							if ((status == VirtualMachineToolsStatus.toolsOk) || (status == VirtualMachineToolsStatus.toolsOld)) {
								log.println("VM Tools are running on target VM...");
								break;
							}
							wait(5000);
						}
					}
                }
				
				log.println("Pause for configured wait time...");
				if(waitpause != null)
					synchronized(this) {
						wait(waitpause * 1000);
					}
			}
		
		// We force a reconnection here.  We don't care about the result, because all we want to do
		// is to force Jenkins to retry its connection attempt at this point.
		log.println("Connecting to the target...");
		Future<?> connectOp = vsSlave.connect(true);
		
		// connectOp.get() will wait until then computation is complete,
		// retrieves the result then moves on.
		// in effect, this will wait for connect to finish.
		log.println("Wait until connectected...");
		try {
			connectOp.get();
		} catch(ExecutionException ex) {
			// TODO:
			// You may want to loop here instead of logging, at least until the expiry time is up.
			logger.log(Level.SEVERE, null, ex);
		}
		
		// Alternatively, you can call connectOp.get at this point, and put it in a loop.  If a problem
		// occurs while you're trying to connect, that problem will show up here as a thrown exception.
		// Then, just cram that in a loop--perhaps with a user option to set the reconnect limit--and
		// spin until the connection succeeds.
		//
		// The return value of connectOp.get should be ignored.
		
		// Now we wait
		log.println("Waiting for slave to come back online...");
		vsSlave.waitUntilOnline();
		
		// Verify that we're now online:
		if(!vsSlave.isOnline())
			throw new IOException("Slave did not come online in allowed time");
		log.println("Get declared fields...");
		
		// Stupid launcher is retaining a pointer to an outdated connection, we have to update it.
		final Field launcherChannel;
		try {
			launcherChannel = Launcher.class.getDeclaredField("channel");
		} catch(NoSuchFieldException ex) {
			// Well, guess we can't do anything then.
			logger.log(Level.SEVERE, "Cannot obtain launcher channel", ex);
			return;
		} catch(SecurityException ex) {
			logger.log(Level.SEVERE, "Cannot obtain launcher channel", ex);
			return;
		}

		// Create a new channel for this connection:
		log.println("Set the channel to correct value...");
		Channel newChannel = vsSlave.getChannel();
		launcherChannel.setAccessible(true);
		try {
			launcherChannel.set(launcher, newChannel);
		} catch(Exception ex) {
			// Should never occur
			throw new RuntimeException("An exception occurred while attempting to reset a launcher's channel", ex);
		}
	}

	public String getVsHost() {
		return vsHost;
	}

	public String getVmName() {
		return vmName;
	}

	public String getSnapname() {
		return snapname;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return Scrambler.descramble(password);
	}

	public Integer getWaitpause() {
		return waitpause;
	}

	public Boolean getWaitfortools() {
		return waitfortools;
	}

	public ArrayList<BuildStep> getBuildSteps() {
		return buildSteps;
	}
	
	public String getRunOrder() {
		return runOrder.name();
	}

	// This indicates to Jenkins that this is an implementation of an extension point.
	@Extension
	public static final class DescriptorImpl extends Descriptor<BuildWrapper> {
		public FormValidation doTestConnection(
			@QueryParameter String vsHost,
			@QueryParameter String vmName,
			@QueryParameter String username,
			@QueryParameter String password,
			@QueryParameter String snapname,
			@QueryParameter String pause,
			@QueryParameter String runOrder,
			@QueryParameter Boolean waitForVMTools
		) {
			/* We know that these objects are not null */
			if(vsHost.length() == 0)
				return FormValidation.error("vSphere Host is not specified");

			/* Perform other sanity checks. */
			if(!vsHost.startsWith("https://"))
				return FormValidation.error("vSphere host must start with https://");
			if(vsHost.endsWith("/"))
				return FormValidation.error("vSphere host name must NOT end with a trailing slash");
			if(vmName.length() == 0)
				return FormValidation.error("Virtual Machine Name is not specified");
			if(snapname.length() == 0)
				return FormValidation.error("Snapshot name is not specified");
			if(username.length() == 0)
				return FormValidation.error("Username is not specified");
			if(password.length() == 0)
				return FormValidation.error("Password is not specified");

			try {
				ServiceInstance si = new ServiceInstance(new URL(vsHost + "/sdk"), username, password, true);
				si.currentTime();
			} catch(Exception e) {
				throw new RuntimeException(e);
			}
			return FormValidation.ok("Connected successfully");
		}

		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			return true;
		}
		
		public ListBoxModel doFillRunOrderItems() {
            ListBoxModel items = new ListBoxModel();
			
			for(RunOrder runOrder : RunOrder.values())
                items.add(runOrder.toString(), runOrder.name());
            return items;
        }

		/**
		 * This human readable name is used in the configuration screen.
		 */
		public String getDisplayName() {
			return "Revert to ESX Snapshot";
		}
	}
}
