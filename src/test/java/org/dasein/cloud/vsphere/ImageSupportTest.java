package org.dasein.cloud.vsphere;

/*
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;
*/

import static org.junit.Assert.*;

import java.util.List;

import mockit.NonStrictExpectations;

import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.compute.Architecture;
import org.dasein.cloud.compute.ImageClass;
import org.dasein.cloud.compute.ImageFilterOptions;
import org.dasein.cloud.compute.MachineImage;
import org.dasein.cloud.compute.MachineImageState;
import org.dasein.cloud.compute.Platform;
import org.dasein.cloud.vsphere.compute.server.ImageSupport;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.PropertyFilterSpec;
import com.vmware.vim25.RetrieveOptions;
import com.vmware.vim25.RetrieveResult;

/**
 * User: rogerunwin
 * Date: 14/09/2015
 */
@RunWith(JUnit4.class)
public class ImageSupportTest extends VsphereTestBase {

    @Before
    public void setUp() throws Exception {
        super.setUp();
        ObjectManagement om = new ObjectManagement();
        final ManagedObjectReference containerView = om.readJsonFile("src/test/resources/ImageSupport/containerView.json", ManagedObjectReference.class);
        final ManagedObjectReference viewManager = om.readJsonFile("src/test/resources/ImageSupport/viewManager.json", ManagedObjectReference.class);
        final ManagedObjectReference rootFolder = om.readJsonFile("src/test/resources/ImageSupport/rootFolder.json", ManagedObjectReference.class);
        final RetrieveResult propertiesEx = om.readJsonFile("src/test/resources/ImageSupport/propertiesEx.json", RetrieveResult.class); // WORKS

        new NonStrictExpectations(){
            { serviceContentMock.getViewManager();
              result = viewManager; }
            { serviceContentMock.getRootFolder();
              result = rootFolder; }
        };

        new NonStrictExpectations(){
            { vimPortMock.createContainerView((ManagedObjectReference)any, (ManagedObjectReference)any, (List<String>)any, anyBoolean); 
              result = containerView; }
            { vimPortMock.retrievePropertiesEx((ManagedObjectReference)any, (List<PropertyFilterSpec>)any, (RetrieveOptions)any); 
              result = propertiesEx; }
        };
    }

    @Test
    public void testListImagesAll() throws CloudException, InternalException {
        final ImageSupport imageSupport = new ImageSupport(vsphereMock);

        Iterable<MachineImage> result = imageSupport.listImages(ImageFilterOptions.getInstance());

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
        final ImageSupport imageSupport = new ImageSupport(vsphereMock);

        Iterable<MachineImage> result = imageSupport.listImages(ImageFilterOptions.getInstance().onPlatform(Platform.UBUNTU));

        assertNotNull("return should not be null", result);

        int count = 0;
        for (MachineImage image : result) {
            count++;
        }
        assertTrue("found images should = 2, not " + count, 2 == count);
    }

    public void testListImagesAllDebian() throws CloudException, InternalException {
        final ImageSupport imageSupport = new ImageSupport(vsphereMock);

        Iterable<MachineImage> result = imageSupport.listImages(ImageFilterOptions.getInstance().onPlatform(Platform.DEBIAN));

        assertNotNull("return should not be null", result);

        int count = 0;
        for (MachineImage image : result) {
            count++;
        }
        assertTrue("found images should = 2, not " + count, 2 == count);
    }

    public void testListImagesAllWindows() throws CloudException, InternalException {
        final ImageSupport imageSupport = new ImageSupport(vsphereMock);

        Iterable<MachineImage> result = imageSupport.listImages(ImageFilterOptions.getInstance().onPlatform(Platform.WINDOWS));

        assertNotNull("return should not be null", result);

        int count = 0;
        for (MachineImage image : result) {
            count++;
        }
        assertTrue("found images should = 8, not " + count, 8 == count);
    }

    @Test
    public void getImageDebian() throws CloudException, InternalException {
        final ImageSupport imageSupport = new ImageSupport(vsphereMock);

        MachineImage image = imageSupport.getImage("roger u debian");

        assertEquals("ownerId", image.getProviderOwnerId());
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
        final ImageSupport imageSupport = new ImageSupport(vsphereMock);

        MachineImage image = imageSupport.getImage("ubuntu-twdemo-dcmagent");

        assertEquals("ownerId", image.getProviderOwnerId());
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
        final ImageSupport imageSupport = new ImageSupport(vsphereMock);

        MachineImage image = imageSupport.getImage("dcm-agent-win2012");

        assertEquals("ownerId", image.getProviderOwnerId());
        assertEquals("datacenter-21", image.getProviderRegionId());
        assertEquals("vm-1955", image.getProviderMachineImageId());
        assertEquals(ImageClass.MACHINE, image.getImageClass());
        assertEquals(MachineImageState.ACTIVE, image.getCurrentState());
        assertEquals("dcm-agent-win2012", image.getName());
        assertEquals("Microsoft Windows Server 2012 (64-bit)", image.getDescription());
        assertEquals(Architecture.I64, image.getArchitecture());
        assertEquals(Platform.WINDOWS, image.getPlatform());
    }
}