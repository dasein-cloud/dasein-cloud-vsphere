/**
 * Copyright (C) 2012-2016 Dell, Inc.
 * See annotations for authorship information
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

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
