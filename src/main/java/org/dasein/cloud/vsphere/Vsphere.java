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

import com.vmware.vim25.InvalidLoginFaultMsg;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.NoPermission;
import com.vmware.vim25.RuntimeFaultFaultMsg;
import com.vmware.vim25.ServiceContent;
import com.vmware.vim25.UserSession;
import com.vmware.vim25.VimPortType;
import com.vmware.vim25.VimService;
import org.apache.log4j.Logger;
import org.dasein.cloud.AbstractCloud;
import org.dasein.cloud.AuthenticationException;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.ContextRequirements;
import org.dasein.cloud.GeneralCloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.vsphere.compute.VsphereCompute;
import org.dasein.cloud.vsphere.network.VSphereNetworkServices;
import org.dasein.util.CalendarWrapper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import javax.xml.ws.BindingProvider;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;


/**
 * Add header info here
 * @version 2013.01 initial version
 * @since 2013.01
 */
public class Vsphere extends AbstractCloud {
    private int sessionTimeout = 0;
    private String vimHostname;
    private VsphereConnection vsphereConnection;
    private int apiMajorVersion;

    public String getVimHostname() {
        return vimHostname;
    }

    public int getApiMajorVersion() { return apiMajorVersion; }

    static private final Logger log = getLogger(Vsphere.class);

    static private @Nonnull String getLastItem(@Nonnull String name) {
        int idx = name.lastIndexOf('.');

        if( idx < 0 ) {
            return name;
        }
        else if( idx == (name.length()-1) ) {
            return "";
        }
        return name.substring(idx + 1);
    }

    static public @Nonnull Logger getLogger(@Nonnull Class<?> cls) {
        String pkg = getLastItem(cls.getPackage().getName());

        if( pkg.equals("vsphere") ) {
            pkg = "";
        }
        else {
            pkg = pkg + ".";
        }
        return Logger.getLogger("dasein.cloud.vsphere.std." + pkg + getLastItem(cls.getName()));
    }

    static public @Nonnull Logger getWireLogger(@Nonnull Class<?> cls) {
        return Logger.getLogger("dasein.cloud.skeleton.wire." + getLastItem(cls.getPackage().getName()) + "." + getLastItem(cls.getName()));
    }

    public Vsphere() { 

    }

    @Override
    public @Nonnull String getCloudName() {
        ProviderContext ctx = getContext();
        String name = (ctx == null ? null : ctx.getCloud().getCloudName());

        return (name == null ? "vSphere" : name);
    }

    @Override
    public @Nonnull ContextRequirements getContextRequirements() {

         return new ContextRequirements(
                 new ContextRequirements.Field("apiKey", "The API Keypair", ContextRequirements.FieldType.KEYPAIR, ContextRequirements.Field.ACCESS_KEYS, true),
                 new ContextRequirements.Field("proxyHost", "Proxy host", ContextRequirements.FieldType.TEXT, null, false),
                 new ContextRequirements.Field("proxyPort", "Proxy port", ContextRequirements.FieldType.TEXT, null, false)
         );
    }

    @Nullable
    @Override
    public VSphereNetworkServices getNetworkServices() {
        return new VSphereNetworkServices(this);
    }

    public @Nonnull VsphereConnection getServiceInstance() throws CloudException, InternalException {
        if (vsphereConnection == null ) {
            ProviderContext ctx = getContext();
            ServiceContent serviceContent;
            VimService vimService;
            VimPortType vimPortType;
            UserSession userSession;
            try {
                SSLContext sc = SSLContext.getInstance("SSL");
                sc.getServerSessionContext().setSessionTimeout(sessionTimeout);
                sc.init(null, null, null);

                
                //HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

                ManagedObjectReference servicesInstance = new ManagedObjectReference();
                servicesInstance.setType("ServiceInstance");
                servicesInstance.setValue("ServiceInstance");

                vimService = new com.vmware.vim25.VimService();
                vimPortType = vimService.getVimPort();
                Map<String, Object> ctxt = ((BindingProvider) vimPortType).getRequestContext();

                ctxt.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, ctx.getCloud().getEndpoint());
                ctxt.put(BindingProvider.SESSION_MAINTAIN_PROPERTY, true);

                serviceContent = vimPortType.retrieveServiceContent(servicesInstance);
            } catch (RuntimeFaultFaultMsg e) {
                if (e.getFaultInfo() instanceof NoPermission) {
                    throw new AuthenticationException("NoPermission fault when retrieving service content", e).withFaultType(AuthenticationException.AuthenticationFaultType.FORBIDDEN);
                }
                throw new GeneralCloudException("Error retrieving service content", e);
            } catch (Exception e) {
                throw new GeneralCloudException("Error getting service content", e);
            }

            List<ContextRequirements.Field> fields = getContextRequirements().getConfigurableValues();
            String username = null;
            String password = null;
            try  {
                for (ContextRequirements.Field field : fields ) {
                    if (field.type.equals(ContextRequirements.FieldType.KEYPAIR)){
                        byte[][] keyPair = (byte[][])ctx.getConfigurationValue(field);
                        username = new String(keyPair[0], "utf-8");
                        password = new String(keyPair[1], "utf-8");
                    }
                }
            } catch ( UnsupportedEncodingException e ) {
                throw new InternalException(e);
            }

            try {
                userSession = vimPortType.login(serviceContent.getSessionManager(), username, password, null);
            } catch (InvalidLoginFaultMsg e) {
                throw new AuthenticationException(e.getMessage(), e).withFaultType(AuthenticationException.AuthenticationFaultType.UNAUTHORISED);
            } catch ( RuntimeFaultFaultMsg e ) {
                if (e.getFaultInfo() instanceof NoPermission) {
                    throw new AuthenticationException("NoPermission fault when logging in", e).withFaultType(AuthenticationException.AuthenticationFaultType.FORBIDDEN);
                }
                throw new GeneralCloudException("Error logging in", e);
            } catch (Exception e) {
                throw new GeneralCloudException("Error logging in", e);
            }

            String apiVersion = serviceContent.getAbout().getApiVersion();
            apiVersion = apiVersion.substring(0, apiVersion.indexOf("."));
            apiMajorVersion = Integer.parseInt(apiVersion);

            vsphereConnection = new VsphereConnection(vimService, vimPortType, userSession, serviceContent);
        }
        return vsphereConnection;
    }

    @Override
    public @Nonnull VsphereCompute getComputeServices() {
        return new VsphereCompute(this);
    }

    @Override
    public @Nonnull DataCenters getDataCenterServices() {
        return new DataCenters(this);
    }

    @Override
    public @Nonnull String getProviderName() {
        ProviderContext ctx = getContext();
        String name = (ctx == null ? null : ctx.getCloud().getProviderName());

        return (name == null ? "vSphere" : name);
    }

    @Override
    public @Nullable String testContext() {
        if (log.isTraceEnabled()) {
            log.trace("ENTER - " + Vsphere.class.getName() + ".testContext()");
        }
        try {
            ProviderContext ctx = getContext();

            if (ctx == null) {
                log.warn("No context was provided for testing");
                return null;
            }

            try {
                getServiceInstance();
            } catch (AuthenticationException e) {
                log.error("AuthenticationException when connecting: "+ e.getMessage()+". Cause: "+e.getCause().getMessage());
                return null;
            } catch (Exception e) {
                log.error("Exception getting serviceInstance: "+ e.getMessage());
                return null;
            }

            try {
                if (!getComputeServices().getVirtualMachineSupport().isSubscribed()) {
                    log.error("Unable to verify vm support during testContext");
                    return null;
                }
                return ctx.getAccountNumber();
            } catch (Throwable t) {
                log.error("testContext(): Failed to test vSphere context: " + t.getMessage());
                if( log.isTraceEnabled() ) {
                    t.printStackTrace();
                }
                return null;
            }
        } finally {
            if (log.isTraceEnabled()) {
                log.trace("EXIT - " + Vsphere.class.getName() + ".textContext()");
            }
        }
    }

    private int holdCount = 0;

    @Override
    public void hold() {
        super.hold();
        synchronized( this ) {
            holdCount++;
        }
    }

    @Override
    public void release() {
        synchronized ( this ) {
            if( holdCount > 0 ) {
                holdCount--;
            }
        }
        super.release();
    }

    private Thread closingThread = null;

    @Override
    public void close() {
        synchronized( this ) {
            if( closingThread != null ) {
                return;
            }
            if( holdCount < 1 ) {
                cleanUp();
            }
            else {
                closingThread = new Thread() {
                    public void run() {
                        long timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 20L);

                        synchronized( Vsphere.this ) {
                            while( holdCount > 0 && System.currentTimeMillis() < timeout ) {
                                try { Vsphere.this.wait(5000L); }
                                catch( InterruptedException ignore ) { }
                            }
                            cleanUp();
                            closingThread = null;
                        }
                    }
                };

                closingThread.setDaemon(true);
                closingThread.start();
            }
        }
    }

    private void cleanUp() {
        super.close();
        try {
            if (vsphereConnection != null) {
                vsphereConnection.getVimPort().logout(vsphereConnection.getServiceContent().getSessionManager());
                vsphereConnection = null;
            }
        }
        catch( NullPointerException ignore ) {
            // ignore
        }
        catch ( RuntimeFaultFaultMsg ignore ) {
            // ignore
        }
    }
}