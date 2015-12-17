package org.dasein.cloud.vsphere;

import static org.junit.Assert.*;

import java.util.List;

import com.vmware.vim25.*;
import mockit.Expectations;
import mockit.Mocked;
import mockit.NonStrictExpectations;

import org.dasein.cloud.*;
import org.dasein.cloud.compute.*;
import org.dasein.cloud.vsphere.compute.Vm;
import org.dasein.cloud.vsphere.compute.VsphereCompute;
import org.dasein.cloud.vsphere.compute.ImageSupport;
import org.dasein.util.uom.time.TimePeriod;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * User: rogerunwin
 * Date: 14/09/2015
 */
@RunWith(JUnit4.class)
public class ImageSupportTest extends VsphereTestBase {
    static private RetrieveResult images;
    static private VirtualMachine vmForCapture;
    static private ManagedObjectReference task;
    static private PropertyChange cloneResult;
    static private MachineImage newImage;
    static private RetrieveResult postRemoveImages;
    static private RetrieveResult imageNoConfigProperty;
    static private RetrieveResult vmList;

    private ImageSupport img;
    private VsphereMethod method = null;
    private List<PropertySpec> templatePSpec;

    @Mocked
    VsphereCompute computeMock;
    @Mocked
    Vm vmMock;

    @BeforeClass
    static public void setupFixtures() {
        ObjectManagement om = new ObjectManagement();
        images = om.readJsonFile("src/test/resources/ImageSupport/propertiesEx.json", RetrieveResult.class);
        vmForCapture = om.readJsonFile("src/test/resources/ImageSupport/vmForCapture.json", VirtualMachine.class);
        task = om.readJsonFile("src/test/resources/ImageSupport/task.json", ManagedObjectReference.class);
        cloneResult = om.readJsonFile("src/test/resources/ImageSupport/cloneResult.json", PropertyChange.class);
        newImage = om.readJsonFile("src/test/resources/ImageSupport/newImage.json", MachineImage.class);
        postRemoveImages = om.readJsonFile("src/test/resources/ImageSupport/postRemoveImages.json", RetrieveResult.class);
        imageNoConfigProperty = om.readJsonFile("src/test/resources/ImageSupport/imagesNoSummaryConfigProperty.json", RetrieveResult.class);
        vmList = om.readJsonFile("src/test/resources/ImageSupport/allVms.json", RetrieveResult.class);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        img = new ImageSupport(vsphereMock);
        method = new VsphereMethod(vsphereMock);
        templatePSpec = img.getTemplatePSpec();
    }

    @Test
    public void testListImagesAll() throws CloudException, InternalException {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = images;
            }
        };

        Iterable<MachineImage> result = img.listImages(ImageFilterOptions.getInstance());

        assertNotNull("return should not be null", result);

        int count = 0;
        for (MachineImage image : result) {
            count++;
            assertNotNull("Returned image ProviderOwnerId should not be null", image.getProviderOwnerId());
            assertNotNull("Returned image ProviderRegionId should not be null", image.getProviderRegionId());
            assertNotNull("Returned image ProviderMachineImageId should not be null", image.getProviderMachineImageId());
            assertNotNull("Returned image ImageClass should not be null", image.getImageClass());
            assertNotNull("Returned image CurrentState should not be null", image.getCurrentState());
            assertNotNull("Returned image Name should not be null", image.getName());
            assertNotNull("Returned image Description should not be null", image.getDescription());
            assertNotNull("Returned image Architecture should not be null", image.getArchitecture());
            assertNotNull("Returned image Platform should not be null", image.getPlatform());
        }
        assertTrue("found images should = 12, not " + count, 12 == count);
    }

    @Test
    public void testListImagesAllUbuntu() throws CloudException, InternalException {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = images;
            }
        };

        Iterable<MachineImage> result = img.listImages(ImageFilterOptions.getInstance().onPlatform(Platform.UBUNTU));

        assertNotNull("return should not be null", result);

        int count = 0;
        for (MachineImage image : result) {
            count++;
        }
        assertTrue("found images should = 2, not " + count, 2 == count);
    }

    @Test
    public void testListImagesAllDebian() throws CloudException, InternalException {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = images;
            }
        };

        Iterable<MachineImage> result = img.listImages(ImageFilterOptions.getInstance().onPlatform(Platform.DEBIAN));

        assertNotNull("return should not be null", result);

        int count = 0;
        for (MachineImage image : result) {
            count++;
        }
        assertTrue("found images should = 3, not " + count, 3 == count);
    }

    @Test
    public void testListImagesAllWindows() throws CloudException, InternalException {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = images;
            }
        };

        Iterable<MachineImage> result = img.listImages(ImageFilterOptions.getInstance().onPlatform(Platform.WINDOWS));

        assertNotNull("return should not be null", result);

        int count = 0;
        for (MachineImage image : result) {
            count++;
        }
        assertTrue("found images should = 7, not " + count, 7 == count);
    }

    @Test
    public void getImageDebian() throws CloudException, InternalException {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = images;
            }
        };

        MachineImage image = img.getImage("roger u debian");

        assertEquals("TESTACCOUNTNO", image.getProviderOwnerId());
        assertEquals("datacenter-21", image.getProviderRegionId());
        assertEquals("vm-1823", image.getProviderMachineImageId());
        assertEquals(ImageClass.MACHINE, image.getImageClass());
        assertEquals(MachineImageState.ACTIVE, image.getCurrentState());
        assertEquals("roger u debian", image.getName());
        assertEquals("Debian GNU/Linux 7 (64-bit)", image.getDescription());
        assertEquals(Architecture.I64, image.getArchitecture());
        assertEquals(Platform.DEBIAN, image.getPlatform());
    }

    @Test
    public void getImageUbuntu() throws CloudException, InternalException {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = images;
            }
        };

        MachineImage image = img.getImage("ubuntu-twdemo-dcmagent");

        assertEquals("TESTACCOUNTNO", image.getProviderOwnerId());
        assertEquals("datacenter-21", image.getProviderRegionId());
        assertEquals("vm-2093", image.getProviderMachineImageId());
        assertEquals(ImageClass.MACHINE, image.getImageClass());
        assertEquals(MachineImageState.ACTIVE, image.getCurrentState());
        assertEquals("ubuntu-twdemo-dcmagent", image.getName());
        assertEquals("Ubuntu Linux (64-bit)", image.getDescription());
        assertEquals(Architecture.I64, image.getArchitecture());
        assertEquals(Platform.UBUNTU, image.getPlatform());
    }

    @Test
    public void getImageWindows() throws CloudException, InternalException {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = images;
            }
        };

        MachineImage image = img.getImage("dcm-agent-win2012");

        assertEquals("TESTACCOUNTNO", image.getProviderOwnerId());
        assertEquals("datacenter-21", image.getProviderRegionId());
        assertEquals("vm-1955", image.getProviderMachineImageId());
        assertEquals(ImageClass.MACHINE, image.getImageClass());
        assertEquals(MachineImageState.ACTIVE, image.getCurrentState());
        assertEquals("dcm-agent-win2012", image.getName());
        assertEquals("Microsoft Windows Server 2012 (64-bit)", image.getDescription());
        assertEquals(Architecture.I64, image.getArchitecture());
        assertEquals(Platform.WINDOWS, image.getPlatform());
    }

    @Test
    public void listImagesShouldReturnEmptyListIfCloudReturnsNullObject() throws CloudException, InternalException {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = null;
            }
        };

        Iterable<MachineImage> images = img.listImages(ImageFilterOptions.getInstance());
        assertNotNull("List may be empty but should not be null", images);
        assertFalse("Cloud returned null but image list is not empty", images.iterator().hasNext());
    }

    @Test
    public void listImagesShouldReturnEmptyListIfCloudReturnsEmptyObject() throws CloudException, InternalException {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = new RetrieveResult();
            }
        };

        Iterable<MachineImage> images = img.listImages(ImageFilterOptions.getInstance());
        assertNotNull("List may be empty but should not be null", images);
        assertFalse("Cloud returned empty list but image list is not empty", images.iterator().hasNext());
    }

    @Test
    public void listImagesShouldReturnEmptyListIfCloudReturnsEmptyPropertySet() throws CloudException, InternalException {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                RetrieveResult rr = new RetrieveResult();
                ObjectContent oc = new ObjectContent();
                oc.setObj(new ManagedObjectReference());
                rr.getObjects().add(oc);
                result = rr;
            }
        };

        Iterable<MachineImage> images = img.listImages(ImageFilterOptions.getInstance());
        assertNotNull("List may be empty but should not be null", images);
        assertFalse("Cloud returned empty property set but image list is not empty", images.iterator().hasNext());
    }

    @Test
    public void listImagesShouldReturnEmptyListIfCloudDoesNotReturnConfigSummaryProperty() throws CloudException, InternalException {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = imageNoConfigProperty;
            }
        };

        Iterable<MachineImage> images = img.listImages(ImageFilterOptions.getInstance());
        assertNotNull("List may be empty but should not be null", images);
        assertFalse("Cloud did not return config property but image list is not empty", images.iterator().hasNext());
    }

    @Test
    public void listImagesShouldReturnEmptyListIfAllObjectsReturnedAreNotTemplates() throws CloudException, InternalException {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = vmList;
            }
        };

        Iterable<MachineImage> images = img.listImages(ImageFilterOptions.getInstance());
        assertNotNull("List may be empty but should not be null", images);
        assertFalse("Cloud did not return any templates but image list is not empty", images.iterator().hasNext());
    }

    @Test
    public void listImagesShouldReturnEmptyListIfNoObjectsMatchOptions() throws CloudException, InternalException {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = images;
            }
        };

        Iterable<MachineImage> images = img.listImages(ImageFilterOptions.getInstance(ImageClass.KERNEL));
        assertNotNull("List may be empty but should not be null", images);
        assertFalse("None of the templates match the filter options (all images are ImageClass.MACHINE) but image list is not empty", images.iterator().hasNext());
    }

    @Test
    public void listImagesShouldReturnFullListIfFilterOptionsIsNull() throws CloudException, InternalException {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = images;
            }
        };

        Iterable<MachineImage> images = img.listImages((ImageFilterOptions) null);
        assertNotNull("List should not be null", images);

        int count = 0;
        for (MachineImage image : images) {
            count++;
        }
        assertTrue("found images should = 12, not " + count, 12 == count);
    }

    @Test(expected = InternalException.class)
    public void listImagesShouldThrowExceptionIfNullRegionId() throws CloudException, InternalException {
        new Expectations(ImageSupport.class) {
            {providerContextMock.getRegionId();
                result = null;
            }
        };

        img.listImages(ImageFilterOptions.getInstance());
    }

    @Test
    public void captureImage() throws CloudException, InternalException {
        new Expectations(ImageSupport.class){
            { img.getImage(anyString);
                result = newImage;
            }
        };

        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = computeMock;
            }
            {computeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = vmForCapture;
            }
            {vmMock.cloneVmTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
            }
            {method.getTaskResult();
                result = cloneResult;
            }
        };

        MachineImage image = img.captureImage(ImageCreateOptions.getInstance(vmForCapture, "captureTest", "capture image unit test"));
        assertNotNull("No image found after capture operation", image);
        assertEquals("captureTest", image.getName());
        assertEquals("capture image unit test", image.getDescription());
        assertEquals("TESTACCOUNTNO", image.getProviderOwnerId());
        assertEquals("datacenter-21", image.getProviderRegionId());
        assertEquals("vm-2528", image.getProviderMachineImageId());
        assertEquals(ImageClass.MACHINE, image.getImageClass());
        assertEquals(MachineImageState.ACTIVE, image.getCurrentState());
        assertEquals(Architecture.I64, image.getArchitecture());
        assertEquals(Platform.UBUNTU, image.getPlatform());
    }

    @Test(expected = InternalException.class)
    public void captureImageShouldThrowExceptionIfNullVmId() throws CloudException, InternalException {
        final ImageCreateOptions optionsMock = ImageCreateOptions.getInstance(vmForCapture, "name", "description");
        new Expectations(ImageCreateOptions.class) {
            {optionsMock.getVirtualMachineId();
                result = null;
            }
        };

        img.captureImage(optionsMock);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void captureImageShouldThrowExceptionIfNullVm() throws CloudException, InternalException {
        final ImageCreateOptions optionsMock = ImageCreateOptions.getInstance(vmForCapture, "name", "description");

        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = computeMock;
            }

            {computeMock.getVirtualMachineSupport();
                result = vmMock;
            }

            {vmMock.getVirtualMachine(anyString);
                result = null;
            }
        };

        img.captureImage(optionsMock);
    }

    @Test(expected = GeneralCloudException.class)
    public void captureImageShouldThrowExceptionIfCaptureTaskIsNotSuccessful() throws CloudException, InternalException {
        new NonStrictExpectations() {
            {vsphereMock.getComputeServices();
                result = computeMock;
            }
            {computeMock.getVirtualMachineSupport();
                result = vmMock;
            }
            {vmMock.getVirtualMachine(anyString);
                result = vmForCapture;
            }
            {vmMock.cloneVmTask((ManagedObjectReference) any, (ManagedObjectReference) any, anyString, (VirtualMachineCloneSpec) any);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = false;
            }
            {method.getTaskError().getVal();
                result = "Capture failed";
            }
        };

        img.captureImage(ImageCreateOptions.getInstance(vmForCapture, "name", "description"));
    }

    @Test
    public void removeImage() throws CloudException, InternalException, RuntimeFaultFaultMsg, VimFaultFaultMsg {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = images;
                result = postRemoveImages;
            }
        };

        new NonStrictExpectations() {
            {vimPortMock.destroyTask((ManagedObjectReference) any);
                result = task;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                result = true;
            }
        };

        img.remove("vm-1823");
        MachineImage image = img.getImage("vm-1823");
        assertNull("Image deleted but still found", image);
    }

    @Test
    public void removeImageShouldDoNothingIfImageNotFound() throws CloudException, InternalException, RuntimeFaultFaultMsg, VimFaultFaultMsg {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = images;
                times = 1;
            }
        };

        new NonStrictExpectations() {
            {vimPortMock.destroyTask((ManagedObjectReference) any);
                times = 0;
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                times = 0;
            }
        };

        img.remove("MyFakeId");
    }

    @Test(expected = GeneralCloudException.class)
    public void removeImageShouldThrowCloudExceptionIfDestroyTaskHasRuntimeFault() throws CloudException, InternalException, RuntimeFaultFaultMsg, VimFaultFaultMsg {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = images;
                times = 1;
            }
        };

        new NonStrictExpectations() {
            {vimPortMock.destroyTask((ManagedObjectReference) any);
                result = new RuntimeFaultFaultMsg("test exception", new RuntimeFault());
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                times = 0;
            }
        };

        img.remove("vm-1823");
    }

    @Test(expected = GeneralCloudException.class)
    public void removeImageShouldThrowExceptionIfDestroyTaskHasVimFault() throws CloudException, InternalException, RuntimeFaultFaultMsg, VimFaultFaultMsg {
        new Expectations(ImageSupport.class){
            { img.retrieveObjectList(vsphereMock, "vmFolder", null, templatePSpec);
                result = images;
                times = 1;
            }
        };

        new NonStrictExpectations() {
            {vimPortMock.destroyTask((ManagedObjectReference) any);
                result = new VimFaultFaultMsg("test exception", new VimFault());
            }
        };

        new Expectations(VsphereMethod.class) {
            {method.getOperationComplete((ManagedObjectReference) any, (TimePeriod) any, anyInt);
                times = 0;
            }
        };

        img.remove("vm-1823");
    }
}