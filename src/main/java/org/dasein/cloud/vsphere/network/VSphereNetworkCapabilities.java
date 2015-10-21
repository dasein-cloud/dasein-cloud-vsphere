package org.dasein.cloud.vsphere.network;

import org.dasein.cloud.*;
import org.dasein.cloud.network.IPVersion;
import org.dasein.cloud.network.VLANCapabilities;
import org.dasein.cloud.util.NamingConstraints;
import org.dasein.cloud.vsphere.Vsphere;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Locale;

/**
 * User: daniellemayne
 * Date: 21/09/2015
 * Time: 12:34
 */
public class VSphereNetworkCapabilities extends AbstractCapabilities<Vsphere> implements VLANCapabilities {

    VSphereNetworkCapabilities(Vsphere provider) {
        super(provider);
    }

    @Override
    public boolean allowsNewNetworkInterfaceCreation() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean allowsNewVlanCreation() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean allowsNewRoutingTableCreation() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean allowsNewSubnetCreation() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean allowsMultipleTrafficTypesOverSubnet() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean allowsMultipleTrafficTypesOverVlan() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean allowsDeletionOfReservedSubnets() throws CloudException, InternalException {
        return false;
    }

    @Override
    public int getMaxNetworkInterfaceCount() throws CloudException, InternalException {
        return LIMIT_UNKNOWN;
    }

    @Override
    public int getMaxVlanCount() throws CloudException, InternalException {
        return LIMIT_UNKNOWN;
    }

    @Nonnull
    @Override
    public String getProviderTermForNetworkInterface(@Nonnull Locale locale) {
        return "";
    }

    @Nonnull
    @Override
    public String getProviderTermForSubnet(@Nonnull Locale locale) {
        return "";
    }

    @Nonnull
    @Override
    public String getProviderTermForVlan(@Nonnull Locale locale) {
        return "network";
    }

    @Nonnull
    @Override
    public Requirement getRoutingTableSupport() throws CloudException, InternalException {
        return Requirement.NONE;
    }

    @Nonnull
    @Override
    public Requirement getSubnetSupport() throws CloudException, InternalException {
        return Requirement.NONE;
    }

    @Nullable
    @Override
    public VisibleScope getVLANVisibleScope() {
        return null;
    }

    @Nonnull
    @Override
    public Requirement identifySubnetDCRequirement() throws CloudException, InternalException {
        return Requirement.NONE;
    }

    @Override
    public boolean isNetworkInterfaceSupportEnabled() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean isSubnetDataCenterConstrained() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean isVlanDataCenterConstrained() throws CloudException, InternalException {
        return false;
    }

    @Nonnull
    @Override
    public Iterable<IPVersion> listSupportedIPVersions() throws CloudException, InternalException {
        ArrayList<IPVersion> list = new ArrayList<IPVersion>();
        list.add(IPVersion.IPV4);
        list.add(IPVersion.IPV6);
        return list;
    }

    @Override
    public boolean supportsInternetGatewayCreation() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean supportsRawAddressRouting() throws CloudException, InternalException {
        return false;
    }

    @Nonnull
    @Override
    public NamingConstraints getVlanNamingConstraints() throws CloudException, InternalException {
        return NamingConstraints.getAlphaNumeric(1, 30).constrainedBy(new char[] {'-'}).lowerCaseOnly();
    }
}
