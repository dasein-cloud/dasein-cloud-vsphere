package org.dasein.cloud.vsphere;

import com.vmware.vim25.ServiceContent;
import com.vmware.vim25.UserSession;
import com.vmware.vim25.VimPortType;
import com.vmware.vim25.VimService;

/*
 * vSphere connection appears to have more than one class needed for access
 * This is a convenience class to gather them together for ease of access.
 * Some of the attributes may prove unneeded, and be pruned in the future.
 */

/**
 * @author Roger Unwin
 * This class holds objects connected with the vSphere connection
 */
public class VsphereConnection {
    private VimService vimService = null;
    private VimPortType vimPortType = null;
    private UserSession userSession = null;
    private ServiceContent serviceContent = null;

    VsphereConnection(VimService vimService, VimPortType vimPortType, UserSession userSession, ServiceContent serviceContent) {
        this.vimService = vimService;
        this.vimPortType = vimPortType;
        this.userSession = userSession;
        this.serviceContent = serviceContent;
    }

    /**
     * @return the VimService of the connection
     */
    public VimService getVimService() {
        return vimService;
    }

    /**
     * @return the VimPortType of the connection
     */
    public VimPortType getVimPort() {
        return vimPortType;
    }

    /**
     * @return the userSession of the connection
     */
    public UserSession getUserSession() {
        return userSession;
    }

    /**
     * @return the serviceContent of the connection
     */
    public ServiceContent getServiceContent() {
        return serviceContent;
    }
}