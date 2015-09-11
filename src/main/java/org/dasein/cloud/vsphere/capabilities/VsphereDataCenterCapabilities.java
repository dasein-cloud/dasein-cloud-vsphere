package org.dasein.cloud.vsphere.capabilities;


import java.util.Locale;

import org.dasein.cloud.AbstractCapabilities;
import org.dasein.cloud.dc.DataCenterCapabilities;
import org.dasein.cloud.vsphere.Vsphere;

public class VsphereDataCenterCapabilities extends AbstractCapabilities<Vsphere> implements DataCenterCapabilities {

    public VsphereDataCenterCapabilities(Vsphere provider) {
        super(provider);
    }

    @Override
    public String getProviderTermForDataCenter(Locale locale) {
        return "cluster";
    }

    @Override
    public String getProviderTermForRegion(Locale locale) {
        return "datacenter";
    }

    @Override
    public boolean supportsAffinityGroups() {
        return true;
    }

    @Override
    public boolean supportsResourcePools() {
        return true;
    }

    @Override
    public boolean supportsStoragePools() {
        return true;
    }

    @Override
    public boolean supportsFolders() {
        return true;
    }

}
