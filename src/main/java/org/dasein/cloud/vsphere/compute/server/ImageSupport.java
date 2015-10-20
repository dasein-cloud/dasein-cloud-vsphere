package org.dasein.cloud.vsphere.compute.server;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.log4j.Logger;
import org.dasein.cloud.AsynchronousTask;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.compute.AbstractImageSupport;
import org.dasein.cloud.compute.Architecture;
import org.dasein.cloud.compute.ImageClass;
import org.dasein.cloud.compute.ImageCopyOptions;
import org.dasein.cloud.compute.ImageCreateOptions;
import org.dasein.cloud.compute.ImageFilterOptions;
import org.dasein.cloud.compute.MachineImage;
import org.dasein.cloud.compute.MachineImageState;
import org.dasein.cloud.compute.Platform;
import org.dasein.cloud.vsphere.Vsphere;
import org.dasein.cloud.vsphere.VsphereConnection;
import org.dasein.cloud.vsphere.VsphereMethod;
import org.dasein.cloud.vsphere.VsphereTraversalSpec;
import org.dasein.cloud.vsphere.capabilities.VsphereImageCapabilities;

import com.vmware.vim25.CustomizationFaultFaultMsg;
import com.vmware.vim25.CustomizationSpec;
import com.vmware.vim25.DynamicProperty;
import com.vmware.vim25.FileFaultFaultMsg;
import com.vmware.vim25.InsufficientResourcesFaultFaultMsg;
import com.vmware.vim25.InvalidDatastoreFaultMsg;
import com.vmware.vim25.InvalidPropertyFaultMsg;
import com.vmware.vim25.InvalidStateFaultMsg;
import com.vmware.vim25.ManagedEntityStatus;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.MigrationFaultFaultMsg;
import com.vmware.vim25.ObjectContent;
import com.vmware.vim25.PropertyFilterSpec;
import com.vmware.vim25.RetrieveOptions;
import com.vmware.vim25.RetrieveResult;
import com.vmware.vim25.RuntimeFaultFaultMsg;
import com.vmware.vim25.SelectionSpec;
import com.vmware.vim25.ServiceContent;
import com.vmware.vim25.TaskInProgressFaultMsg;
import com.vmware.vim25.VimPortType;
import com.vmware.vim25.VirtualMachineCloneSpec;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.VirtualMachineConfigSummary;
import com.vmware.vim25.VirtualMachineRelocateSpec;
import com.vmware.vim25.VmConfigFaultFaultMsg;

import org.dasein.cloud.util.APITrace;
import org.dasein.util.uom.time.Second;
import org.dasein.util.uom.time.TimePeriod;


/**
 *
 */
public class ImageSupport extends AbstractImageSupport<Vsphere> {
    private Vsphere provider;
    private VsphereImageCapabilities capabilities;
    static private final Logger logger = Vsphere.getLogger(ImageSupport.class);

    public ImageSupport(@Nonnull Vsphere provider) {
        super(provider);
        this.provider = provider;
    }

    @Override
    public VsphereImageCapabilities getCapabilities() throws CloudException, InternalException {
        if (capabilities == null) {
            capabilities = new VsphereImageCapabilities(provider);
        }
        return capabilities;
    }

    @Override
    public MachineImage getImage(String providerImageId) throws CloudException, InternalException {
        ImageFilterOptions options = ImageFilterOptions.getInstance(true, providerImageId);
        // TODO Auto-generated method stub
        Iterable<MachineImage> images = listImages(options);

        return images.iterator().next();
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        return true;
    }

    public List<PropertyFilterSpec> getlistImagesPropertyFilterSpec(ServiceContent serviceContent, VimPortType vimPort) throws RuntimeFaultFaultMsg, CloudException, InternalException {
        ManagedObjectReference viewManager = serviceContent.getViewManager();
        ManagedObjectReference rootFolder = serviceContent.getRootFolder();

        ManagedObjectReference cViewRef = vimPort.createContainerView(viewManager, rootFolder, Collections.singletonList("VirtualMachine"), true);

        VsphereTraversalSpec vsphereTraversalSpec 
            = new VsphereTraversalSpec("traverseEntities", "view", "ContainerView", false)
        .withObjectSpec(cViewRef, false)
        .withPropertySpec("VirtualMachine", "summary.config", "summary.overallStatus").finalizeTraversalSpec();
        return vsphereTraversalSpec.getPropertyFilterSpecList();

    }

    @Override
    public Iterable<MachineImage> listImages(@Nullable ImageFilterOptions opts) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "ImageSupport.listImages");

        VsphereConnection vsphereConnection = provider.getServiceInstance();
        VimPortType vimPort = vsphereConnection.getVimPort();
        ServiceContent serviceContent = vsphereConnection.getServiceContent();

        final ImageFilterOptions options;

        if (opts == null) {
            options = ImageFilterOptions.getInstance();
        } else {
            options = opts;
        }

        ArrayList<MachineImage> machineImages = new ArrayList<MachineImage>();

        try {
            String regionId = getProvider().getContext().getRegionId();

            List<PropertyFilterSpec> fSpecList = getlistImagesPropertyFilterSpec(serviceContent, vimPort);

            // get the data from the server
            RetrieveResult props = null;
            try {
                props = vimPort.retrievePropertiesEx(serviceContent.getPropertyCollector(), fSpecList, new RetrieveOptions());
            } catch ( InvalidPropertyFaultMsg e ) {
                throw new CloudException(e);
            }

            if (props != null) {
                for (ObjectContent oc : props.getObjects()) {
                    ManagedObjectReference templateRef = oc.getObj();
                    Platform platform = null;
                    String name = null;
                    String description = null;
                    String imageId = null;
                    MachineImageState state = MachineImageState.ERROR;
                    Architecture architecture = Architecture.I64;

                    VirtualMachineConfigSummary virtualMachineConfigSummary = null;
                    List<DynamicProperty> dps = oc.getPropSet();
                    if (dps != null) {
                         for (DynamicProperty dp : dps) {
                              if (dp.getName().equals("summary.config")) {
                                 virtualMachineConfigSummary = (VirtualMachineConfigSummary) dp.getVal();
                             } else if (dp.getName().equals("summary.overallStatus")) {
                                 ManagedEntityStatus s = (ManagedEntityStatus) dp.getVal();
                                 if (s.equals(ManagedEntityStatus.GREEN)) {
                                     state = MachineImageState.ACTIVE;
                                 }
                             }
                         }
                         name = virtualMachineConfigSummary.getName();
                         description = virtualMachineConfigSummary.getGuestFullName();
                         imageId = templateRef.getValue();
                         platform = Platform.guess(virtualMachineConfigSummary.getGuestFullName());
                         ImageClass imageClass = ImageClass.MACHINE;

                         if (virtualMachineConfigSummary.isTemplate()) { 
                            MachineImage machineImage = MachineImage.getInstance(
                                     "ownerId",
                                     regionId,
                                     imageId,
                                     imageClass,
                                     state,
                                     name,
                                     description,
                                     architecture,
                                     platform);
                            if (options.matches(machineImage)) {
                                machineImages.add(machineImage);
                            }
                         }
                     }
                 }
             }
        } catch ( RuntimeFaultFaultMsg e ) {
            throw new CloudException(e);
        } catch ( Exception e ) {
            throw new CloudException(e);
        } finally {
            APITrace.end();
        }

        return machineImages;
    }

    @Override
    protected MachineImage capture(@Nonnull ImageCreateOptions options, @Nullable AsynchronousTask<MachineImage> task) throws CloudException, InternalException {
        // TODO Auto-generated method stub  capture vm to template
        APITrace.begin(getProvider(), "ImageSupport.listImages");

        VsphereConnection vsphereConnection = provider.getServiceInstance();
        VimPortType vimPort = vsphereConnection.getVimPort();
        ServiceContent serviceContent = vsphereConnection.getServiceContent();
        try {

        } finally {
            APITrace.end();
        }

        return null;
    }

    // WIP - not yet working.
    @Override
    public @Nonnull String copyImage(@Nonnull ImageCopyOptions options) throws CloudException, InternalException {
        // TODO Auto-generated method stub - copy template to template
        APITrace.begin(getProvider(), "ImageSupport.listImages");

        // INPUTS
        options.getDescription(); // <- description for clone.
        
        

        VsphereConnection vsphereConnection = provider.getServiceInstance();
        VimPortType vimPort = vsphereConnection.getVimPort();
        ServiceContent serviceContent = vsphereConnection.getServiceContent();

        try {
            // VMClone.java

            VirtualMachineCloneSpec cloneSpec = new VirtualMachineCloneSpec();
            VirtualMachineRelocateSpec relocSpec = new VirtualMachineRelocateSpec();
            ManagedObjectReference arg0 = null;
            relocSpec.setPool(arg0);
            relocSpec.setHost(arg0);
            relocSpec.setFolder(arg0);
            relocSpec.setDatastore(arg0);
                cloneSpec.setLocation(relocSpec);
                cloneSpec.setPowerOn(false);
                cloneSpec.setTemplate(true);
                    //VirtualMachineConfigSpec virtualMachineConfigSpec = new VirtualMachineConfigSpec();
                    //--nothing here unless you want to change the template vm requirements...
                    //cloneSpec.setConfig(virtualMachineConfigSpec);
                    //CustomizationSpec customizationSpec = new CustomizationSpec();
                    //-- cant set description here...
                    //cloneSpec.setCustomization(customizationSpec);
            ManagedObjectReference datacenterRef = vimPort.findByInventoryPath(serviceContent.getSearchIndex(), options.getTargetRegionId());




            //TODO check DM code for something to return a folder ref
            ManagedObjectReference vmFolderRef = null; //getDynamicProperty(datacenterRef, "vmFolder");






            String vmPathName = null; //options.getProviderImageId();options.getTargetRegionId(); // <- image to clone -- HOW TO CONVERT TO --> vmPathName

            ManagedObjectReference vmRef = vimPort.findByInventoryPath(serviceContent.getSearchIndex(), vmPathName);
            ManagedObjectReference cloneTask = vimPort.cloneVMTask(vmRef, vmFolderRef, options.getName(), cloneSpec);
            VsphereMethod method = new VsphereMethod(provider);
            TimePeriod<Second> interval = new TimePeriod<Second>(10, TimePeriod.SECOND);
            method.getOperationComplete(cloneTask, interval, 20);
        } catch ( Exception e ) {
            throw new CloudException(e);
        } finally {
            APITrace.end();
        }
        return options.getName();
    }

    SelectionSpec getSelectionSpec(String name) {
        SelectionSpec genericSpec = new SelectionSpec();
        genericSpec.setName(name);
        return genericSpec;
    }

    List<ObjectContent> retrievePropertiesAllObjects(List<PropertyFilterSpec> listpfs) throws RuntimeFaultFaultMsg, InvalidPropertyFaultMsg, CloudException, InternalException {
        VsphereConnection vsphereConnection = provider.getServiceInstance();
        VimPortType vimPort = vsphereConnection.getVimPort();
        ServiceContent serviceContent = vsphereConnection.getServiceContent();

        RetrieveOptions propObjectRetrieveOpts = new RetrieveOptions();
        List<ObjectContent> listobjcontent = new ArrayList<ObjectContent>();
        RetrieveResult rslts = vimPort.retrievePropertiesEx(serviceContent.getPropertyCollector(), listpfs, propObjectRetrieveOpts);
        if (rslts != null && rslts.getObjects() != null && !rslts.getObjects().isEmpty()) {
            listobjcontent.addAll(rslts.getObjects());
        }
        String token = null;
        if (rslts != null && rslts.getToken() != null) {
            token = rslts.getToken();
        }

        while (token != null && !token.isEmpty()) {
            rslts = vimPort.continueRetrievePropertiesEx(serviceContent.getPropertyCollector(), token);
            token = null;
            if (rslts != null) {
                token = rslts.getToken();
                if (rslts.getObjects() != null && !rslts.getObjects().isEmpty()) {
                    listobjcontent.addAll(rslts.getObjects());
                }
            }
        }
        return listobjcontent;
    }

    @Override
    public void remove(String providerImageId, boolean checkState) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "ImageSupport.remove");

        VsphereConnection vsphereConnection = provider.getServiceInstance();
        VimPortType vimPort = vsphereConnection.getVimPort();
        ServiceContent serviceContent = vsphereConnection.getServiceContent();
        try {
            VsphereTraversalSpec vsphereTraversalSpec = new VsphereTraversalSpec("traverseEntities", "view", "ContainerView", false)
                .withObjectSpec(vimPort.createContainerView(serviceContent.getViewManager(),
                                                            serviceContent.getRootFolder(),
                                                            Collections.singletonList("VirtualMachine"),
                                                            true), false)
                .withPropertySpec("VirtualMachine", "summary.config").finalizeTraversalSpec();

            RetrieveResult props = null;
            try {
                props = vimPort.retrievePropertiesEx(serviceContent.getPropertyCollector(), vsphereTraversalSpec.getPropertyFilterSpecList(), new RetrieveOptions());
            } catch ( InvalidPropertyFaultMsg e ) {
                throw new CloudException(e);
            }

            for (ObjectContent oc : props.getObjects()) {
                ManagedObjectReference templateToBeDeleted = oc.getObj();

                DynamicProperty dp = (DynamicProperty)(oc.getPropSet().iterator().next());
                VirtualMachineConfigSummary virtualMachineConfigSummary = (VirtualMachineConfigSummary)dp.getVal();

                if (providerImageId.equals(virtualMachineConfigSummary.getName()) && virtualMachineConfigSummary.isTemplate()) {
                    ManagedObjectReference taskmor = vimPort.destroyTask(templateToBeDeleted);
                    VsphereMethod method = new VsphereMethod(provider);
                    TimePeriod<Second> interval = new TimePeriod<Second>(5, TimePeriod.SECOND);
                    method.getOperationComplete(taskmor, interval, 20);
                    break;
                }
            }
        } catch ( Exception e ) {
            throw new CloudException(e);
        } finally {
            APITrace.end();
        }
    }
}
