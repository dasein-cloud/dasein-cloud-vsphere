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

package org.dasein.cloud.vsphere;

import com.vmware.vim25.LocalizedMethodFault;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.ObjectSpec;
import com.vmware.vim25.ObjectUpdate;
import com.vmware.vim25.ObjectUpdateKind;
import com.vmware.vim25.PropertyChange;
import com.vmware.vim25.PropertyFilterSpec;
import com.vmware.vim25.PropertyFilterUpdate;
import com.vmware.vim25.PropertySpec;
import com.vmware.vim25.ServiceContent;
import com.vmware.vim25.TaskInfoState;
import com.vmware.vim25.UpdateSet;
import com.vmware.vim25.VimPortType;
import com.vmware.vim25.WaitOptions;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.util.APITrace;
import org.dasein.util.uom.time.Second;
import org.dasein.util.uom.time.TimePeriod;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

public class VsphereMethod {

    private Vsphere provider;

    private PropertyChange taskResult;
    private PropertyChange taskState;
    private PropertyChange taskError;

    public VsphereMethod(@Nonnull Vsphere provider) {
        this.provider = provider;
    }

    public boolean getOperationComplete(ManagedObjectReference taskmor, TimePeriod interval, int repetions) throws CloudException, InternalException {
        APITrace.begin(provider, "VsphereMethod.getOperationComplete");
        Long intervalSeconds = ((TimePeriod<Second>)interval.convertTo(TimePeriod.SECOND)).longValue();
        try {
            for (int iteration = 0; iteration < repetions; iteration++) {
                if (getOperationCurrentStatus(taskmor)) {
                    return true;
                }
                try { Thread.sleep(1000 * intervalSeconds); }
                catch( InterruptedException e ) { }
            }

            return false;
        } finally {
            APITrace.end();
        }
    }

    public boolean getOperationCurrentStatus(ManagedObjectReference taskmor) throws CloudException, InternalException {
        APITrace.begin(provider, "VsphereMethod.getOperationCurrentStatus");

        String version = "";
        List<PropertyFilterUpdate> filtupary;
        List<ObjectUpdate> objupary;

        VsphereConnection vsphereConnection = provider.getServiceInstance();
        VimPortType vimPort = vsphereConnection.getVimPort();
        ServiceContent serviceContent = vsphereConnection.getServiceContent();

        PropertyFilterSpec spec = new PropertyFilterSpec();
        ObjectSpec oSpec = new ObjectSpec();
        oSpec.setObj(taskmor);
        oSpec.setSkip(Boolean.FALSE);
        spec.getObjectSet().add(oSpec);

        PropertySpec pSpec = new PropertySpec();
        pSpec.getPathSet().addAll(Arrays.asList("info.state", "info.error", "info.result"));
        pSpec.setType(taskmor.getType());
        spec.getPropSet().add(pSpec);

        ManagedObjectReference filterSpecRef = null;
        try {
            filterSpecRef = vimPort.createFilter(serviceContent.getPropertyCollector(), spec, true);
            UpdateSet updateset = vimPort.waitForUpdatesEx(serviceContent.getPropertyCollector(), version, new WaitOptions());

            if (updateset == null || updateset.getFilterSet() == null) {
                return false;
            }
            filtupary = updateset.getFilterSet();

            for (PropertyFilterUpdate filtup : filtupary) {
                objupary = filtup.getObjectSet();
                for (ObjectUpdate objup : objupary) {
                    if (objup.getKind() == ObjectUpdateKind.MODIFY || objup.getKind() == ObjectUpdateKind.ENTER || objup.getKind() == ObjectUpdateKind.LEAVE) {
                        for (PropertyChange propchg : objup.getChangeSet()) {
                            switch (propchg.getName()) {
                                case "info.result":
                                    setTaskResult(propchg);
                                    break;
                                case "info.state":
                                    setTaskState(propchg);
                                    break;
                                case "info.error":
                                    setTaskError(propchg);
                                    break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new CloudException(e);
        } finally {
            try {
                vimPort.destroyPropertyFilter(filterSpecRef);
            } catch (Exception e) {
                throw new CloudException(e);
            }
            APITrace.end();
        }
        if (taskState.getVal().equals(TaskInfoState.ERROR)) {
            String detailedMessage = taskError.getVal().toString();
            if (taskError.getVal() instanceof LocalizedMethodFault) {
                detailedMessage = (((LocalizedMethodFault) taskError.getVal()).getLocalizedMessage());
            }
            throw new CloudException("Error waiting on task completion: "+detailedMessage);
        }
        return (null != taskState) && (taskState.getVal().equals(TaskInfoState.SUCCESS));
    }

    public PropertyChange getTaskResult() {
        return taskResult;
    }

    public void setTaskResult(PropertyChange taskResult) {
        this.taskResult = taskResult;
    }

    public PropertyChange getTaskState() {
        return taskState;
    }

    public void setTaskState(PropertyChange taskState) {
        this.taskState = taskState;
    }

    public PropertyChange getTaskError() {
        return taskError;
    }

    public void setTaskError(PropertyChange taskError) {
        this.taskError = taskError;
    }
}