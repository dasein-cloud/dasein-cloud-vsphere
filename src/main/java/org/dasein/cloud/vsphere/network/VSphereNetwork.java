package org.dasein.cloud.vsphere.network;

import com.vmware.vim25.DVPortgroupConfigInfo;
import com.vmware.vim25.DynamicProperty;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.NetworkSummary;
import com.vmware.vim25.ObjectContent;
import com.vmware.vim25.PropertySpec;
import com.vmware.vim25.RetrieveResult;
import com.vmware.vim25.SelectionSpec;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.VisibleScope;
import org.dasein.cloud.network.AbstractVLANSupport;
import org.dasein.cloud.network.IPVersion;
import org.dasein.cloud.network.VLAN;
import org.dasein.cloud.network.VLANCapabilities;
import org.dasein.cloud.network.VLANState;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.cloud.vsphere.Vsphere;
import org.dasein.cloud.vsphere.VsphereInventoryNavigation;
import org.dasein.cloud.vsphere.VsphereTraversalSpec;
import org.dasein.util.uom.time.Day;
import org.dasein.util.uom.time.TimePeriod;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * User: daniellemayne
 * Date: 21/09/2015
 * Time: 12:34
 */
public class VSphereNetwork extends AbstractVLANSupport<Vsphere> {
    private List<PropertySpec> networkPSpec;

    public VSphereNetwork(Vsphere provider) {
        super(provider);
    }

    public RetrieveResult retrieveObjectList(Vsphere provider, @Nonnull String baseFolder, @Nullable List<SelectionSpec> selectionSpecsArr, @Nonnull List<PropertySpec> pSpecs) throws InternalException, CloudException {
        VsphereInventoryNavigation nav = new VsphereInventoryNavigation();
        return nav.retrieveObjectList(provider, baseFolder, selectionSpecsArr, pSpecs);
    }

    public List<PropertySpec> getNetworkPSpec() {
        if (networkPSpec == null) {
            networkPSpec = VsphereTraversalSpec.createPropertySpec(networkPSpec, "Network", true);
        }
        return networkPSpec;
    }

    private transient volatile VSphereNetworkCapabilities capabilities;

    @Override
    public VLANCapabilities getCapabilities() throws CloudException, InternalException {
        if( capabilities == null ) {
            capabilities = new VSphereNetworkCapabilities(getProvider());
        }
        return capabilities;
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        return true;
    }

    @Nonnull
    @Override
    public Iterable<VLAN> listVlans() throws CloudException, InternalException {
        APITrace.begin(getProvider(), "VSphereNetwork.listVlans");
        try {
            ProviderContext ctx = getProvider().getContext();
            Cache<VLAN> cache = Cache.getInstance(getProvider(), "networks", VLAN.class, CacheLevel.REGION_ACCOUNT, new TimePeriod<Day>(1, TimePeriod.DAY));
            Collection<VLAN> netList = (Collection<VLAN>)cache.get(ctx);

            if( netList != null ) {
                return netList;
            }

            List<VLAN> list = new ArrayList<VLAN>();
            List<PropertySpec> pSpecs = getNetworkPSpec();

            RetrieveResult listobcont = retrieveObjectList(getProvider(), "networkFolder", null, pSpecs);

            if (listobcont != null) {
                List<ObjectContent> objectContents = listobcont.getObjects();
                for (ObjectContent oc : objectContents) {
                    ManagedObjectReference mo = oc.getObj();
                    String id = mo.getValue();
                    String networkType = mo.getType();
                    if (networkType.equals("Network") || networkType.equals("DistributedVirtualPortgroup")) {
                        List<DynamicProperty> props = oc.getPropSet();
                        String networkName = null, dvsId = null;
                        boolean state = false;
                        for (DynamicProperty dp : props) {
                            if (dp.getName().equals("summary")) {
                                NetworkSummary ns = (NetworkSummary) dp.getVal();
                                state = ns.isAccessible();
                                networkName = ns.getName();
                            }
                            else if (dp.getVal() instanceof DVPortgroupConfigInfo) {
                                DVPortgroupConfigInfo di = (DVPortgroupConfigInfo) dp.getVal();
                                ManagedObjectReference switchMO = di.getDistributedVirtualSwitch();
                                dvsId = switchMO.getValue();
                            }
                        }
                        if ( networkName != null ) {
                            if (networkType.equals("DistributedVirtualPortgroup")) {
                                if (dvsId == null) {
                                    continue;
                                }
                            }
                            VLAN vlan = toVlan(id, networkName, state, dvsId);
                            if (vlan != null) {
                                list.add(vlan);
                            }
                        }
                    }
                }
            }
            cache.put(ctx, list);
            return list;
        }
        finally {
            APITrace.end();
        }
    }

    private VLAN toVlan(@Nonnull String id, @Nonnull String name, boolean available, @Nullable String switchID) throws InternalException, CloudException {
        VLAN vlan = new VLAN();
        vlan.setName(name);
        vlan.setDescription(name + " ("+id+")");
        vlan.setProviderVlanId(id);
        vlan.setCidr("");
        if( switchID != null) {
            vlan.setTag("switch.uuid", switchID);
        }
        vlan.setProviderRegionId(getContext().getRegionId());
        vlan.setProviderOwnerId(getContext().getAccountNumber());
        vlan.setSupportedTraffic(IPVersion.IPV4);
        vlan.setVisibleScope(VisibleScope.ACCOUNT_REGION);
        vlan.setCurrentState(available ? VLANState.AVAILABLE : VLANState.PENDING);
        return vlan;
    }
}
