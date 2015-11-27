package org.dasein.cloud.vsphere;

import com.vmware.vim25.*;
import mockit.Expectations;
import mockit.Mocked;
import org.dasein.cloud.*;
import org.junit.Test;

import org.apache.log4j.Logger;
import static org.junit.Assert.*;

/**
 * User: daniellemayne
 * Date: 26/11/2015
 * Time: 09:05
 */
public class ConnectionTest {
    private Vsphere vsphere;
    @Mocked
    ProviderContext contextMock;
    @Mocked
    Logger logger;

    @Test
    public void invalidCredentialsShouldReturnNullWhenTestingTheContext() throws CloudException, InternalException, RuntimeFaultFaultMsg{
        vsphere = new Vsphere();
        new Expectations(Vsphere.class) {
            {logger.isTraceEnabled();
                result = false;
            }
            {vsphere.getContext();
                result = contextMock;
            }
            {logger.error("AuthenticationException when connecting: Invalid login credentials. Cause: Username and password match not found");
            }
            {vsphere.getServiceInstance();
                result = new AuthenticationException("Invalid login credentials", AuthenticationException.AuthenticationFaultType.UNAUTHORISED,
                        new InvalidLoginFaultMsg("Username and password match not found", new InvalidLogin()));
            }
        };
        String account = vsphere.testContext();
        assertNull(account);
    }
}
