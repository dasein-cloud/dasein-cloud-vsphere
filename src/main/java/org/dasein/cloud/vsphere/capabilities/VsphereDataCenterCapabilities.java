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
