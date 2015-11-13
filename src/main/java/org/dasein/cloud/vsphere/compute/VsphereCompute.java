package org.dasein.cloud.vsphere.compute;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.dasein.cloud.compute.AbstractComputeServices;
import org.dasein.cloud.vsphere.Vsphere;


public class VsphereCompute extends AbstractComputeServices<Vsphere> {


    public VsphereCompute(Vsphere provider) {
        super(provider);
    }

    @Nullable
    @Override
    public HostSupport getAffinityGroupSupport() {
        return new HostSupport(getProvider());
    }

    @Override
    public @Nullable Vm getVirtualMachineSupport() {
        return new Vm(getProvider());
    }

    @Nullable
    @Override
    public HardDisk getVolumeSupport() { return new HardDisk(getProvider()); }

    public @Nonnull ImageSupport getImageSupport() {
        return new ImageSupport(getProvider());
    }
}
