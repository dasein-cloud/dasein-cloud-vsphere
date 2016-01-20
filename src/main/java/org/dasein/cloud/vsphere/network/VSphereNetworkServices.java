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

package org.dasein.cloud.vsphere.network;

import org.dasein.cloud.network.AbstractNetworkServices;
import org.dasein.cloud.vsphere.Vsphere;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * User: daniellemayne
 * Date: 21/09/2015
 * Time: 12:41
 */
public class VSphereNetworkServices extends AbstractNetworkServices<Vsphere> {
    public VSphereNetworkServices(@Nonnull Vsphere cloud) { super(cloud); }

    @Nullable
    @Override
    public VSphereNetwork getVlanSupport() {
        return new VSphereNetwork(getProvider());
    }
}
