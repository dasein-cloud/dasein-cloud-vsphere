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

import java.util.ArrayList;
import java.util.List;

import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.ObjectSpec;
import com.vmware.vim25.PropertyFilterSpec;
import com.vmware.vim25.PropertySpec;
import com.vmware.vim25.SelectionSpec;
import com.vmware.vim25.TraversalSpec;

public class VsphereTraversalSpec {
    private TraversalSpec traversalSpec = null;
    private PropertyFilterSpec fSpec = new PropertyFilterSpec();
    private List<PropertyFilterSpec> fSpecList = new ArrayList<PropertyFilterSpec>();

    public VsphereTraversalSpec(String name, String path, String type, boolean skip) {
        // create a traversal spec to select all objects in the view
        traversalSpec = new TraversalSpec();
        traversalSpec.setName(name);
        traversalSpec.setPath(path);
        traversalSpec.setType(type);
        traversalSpec.setSkip(skip);
    }

    // can safely call more than once.
    public VsphereTraversalSpec withSelectionSpec(String sSpecName, String tSpecName, String tSpecType, String tSpecPath, boolean tSpecSkip) {
        SelectionSpec sSpec = new SelectionSpec();
        sSpec.setName(sSpecName);

        TraversalSpec selectionSpecTraversalSpec = new TraversalSpec();
        selectionSpecTraversalSpec.setName(tSpecName);
        selectionSpecTraversalSpec.setType(tSpecType);
        selectionSpecTraversalSpec.setPath(tSpecPath);
        selectionSpecTraversalSpec.setSkip(tSpecSkip);
        selectionSpecTraversalSpec.getSelectSet().add(sSpec);

        List<SelectionSpec> selectionSpecsArr = new ArrayList<SelectionSpec>();
        selectionSpecsArr.add(sSpec);
        selectionSpecsArr.add(selectionSpecTraversalSpec);

        traversalSpec.getSelectSet().addAll(selectionSpecsArr);

        return this;
    }

    public VsphereTraversalSpec withSelectionSpec(List<SelectionSpec> selectionSpecsArr) {
        traversalSpec.getSelectSet().addAll(selectionSpecsArr);
        return this;
    }

    public VsphereTraversalSpec withObjectSpec(ManagedObjectReference folder, boolean skip) {
        // create an object spec to define the beginning of the traversal;
        // container view is the root object for this traversal
        ObjectSpec oSpec = new ObjectSpec();
        oSpec.setObj(folder);
        oSpec.getSelectSet().add(traversalSpec);
        oSpec.setSkip(skip);
        fSpec.getObjectSet().add(oSpec);

        return this;
    }

    public VsphereTraversalSpec withPropertySpec(String type, String... paths) {
        // specify the property for retrieval (virtual machine name)
        PropertySpec pSpec = new PropertySpec();
        pSpec.setType(type);
        for (String pSpecPath : paths) {
            pSpec.getPathSet().add(pSpecPath);
        }

        fSpec.getPropSet().add(pSpec);

        return this;
    }

    public static List<PropertySpec> createPropertySpec(List<PropertySpec> propertySpecList, String type, boolean all, String... paths) {
        if (null == propertySpecList) {
            propertySpecList = new ArrayList<PropertySpec>();
        }
        PropertySpec propertySpec = new PropertySpec();
        propertySpec.setAll(all);
        for (String pSpecPath : paths) {
            propertySpec.getPathSet().add(pSpecPath);
        }
        propertySpec.setType(type);

        propertySpecList.add(propertySpec);

        return propertySpecList;
    }

    public VsphereTraversalSpec withPropertySpec(List<PropertySpec> pSpecs) {
        fSpec.getPropSet().addAll(pSpecs);
        return this;
    }

    public List<PropertyFilterSpec> getPropertyFilterSpecList() {
        return fSpecList;
    }

    public VsphereTraversalSpec finalizeTraversalSpec() {
        fSpecList.add(fSpec);
        return this;
    }
}
