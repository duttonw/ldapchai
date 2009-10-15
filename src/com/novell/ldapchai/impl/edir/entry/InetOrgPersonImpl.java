/*
 * LDAP Chai API
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009 Jason D. Rivard
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package com.novell.ldapchai.impl.edir.entry;

import com.novell.ldapchai.AbstractChaiUser;
import com.novell.ldapchai.ChaiConstant;
import com.novell.ldapchai.ChaiGroup;
import com.novell.ldapchai.ChaiPasswordPolicy;
import com.novell.ldapchai.cr.ChallengeSet;
import com.novell.ldapchai.cr.CrFactory;
import com.novell.ldapchai.cr.ResponseSet;
import com.novell.ldapchai.exception.*;
import com.novell.ldapchai.provider.ChaiProvider;
import com.novell.ldapchai.provider.ChaiSetting;
import com.novell.ldapchai.util.StringHelper;
import com.novell.security.nmas.jndi.ldap.ext.*;

import javax.naming.ldap.ExtendedResponse;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Properties;

class InetOrgPersonImpl extends AbstractChaiUser implements InetOrgPerson {
    static {
        EdirEntryFactory.registerImplementation(InetOrgPerson.OBJECT_CLASS_VALUE, AbstractChaiUser.class);
    }

    public String getLdapObjectClassName()
    {
        return InetOrgPerson.OBJECT_CLASS_VALUE;
    }

    InetOrgPersonImpl(final String userDN, final ChaiProvider chaiProvider)
    {
        super(userDN, chaiProvider);
    }

    public ChaiPasswordPolicy getPasswordPolicy()
            throws ChaiUnavailableException, ChaiOperationException
    {
        return EdirEntries.readPasswordPolicy(this);
    }

    public boolean testPassword(final String password)
            throws ChaiUnavailableException, ChaiPasswordPolicyException
    {
        try {
            return this.compareStringAttribute(ATTR_PASSWORD, password);
        } catch (ChaiOperationException e) {
            throw ChaiPasswordPolicyException.forErrorMessage(e.getMessage());
        }
    }

    public boolean testPasswordPolicy(final String password)
            throws ChaiUnavailableException, ChaiPasswordPolicyException
    {
        final boolean useNmasSetting = this.getChaiProvider().getChaiConfiguration().getBooleanSetting(ChaiSetting.EDIRECTORY_ENABLE_NMAS);
        if (!useNmasSetting) {
            return true;
        }

        final PwdPolicyCheckRequest request = new PwdPolicyCheckRequest();
        request.setData(password);
        request.setObjectDN(this.getEntryDN());
        final ExtendedResponse response;
        try {
            response = getChaiProvider().extendedOperation(request);
        } catch (ChaiOperationException e) {
            LOGGER.debug("unexpected error while checking [nmas] password policy: " + e.getMessage());
            return true;
        }
        if (response != null) {
            final PwdPolicyCheckResponse setResponse = (PwdPolicyCheckResponse) response;
            final int responseCode = setResponse.getNmasRetCode();
            if (responseCode != 0) {
                LOGGER.debug("error while testing nmas password: " + responseCode);
                throw ChaiPasswordPolicyException.forErrorCode(responseCode);
            }
        }
        return true;
    }

    public final void unlock()
            throws ChaiOperationException, ChaiUnavailableException
    {
        this.writeStringAttribute(ChaiConstant.ATTR_LDAP_LOCKED_BY_INTRUDER, "FALSE");
        this.writeStringAttribute(ChaiConstant.ATTR_LDAP_LOGIN_INTRUDER_ATTEMPTS, "0");
        this.writeStringAttribute(ChaiConstant.ATTR_LDAP_LOGIN_INTRUDER_RESET_TIME, "19700101010101Z");

        final String limit = this.readStringAttribute(ChaiConstant.ATTR_LDAP_LOGIN_GRACE_LIMIT);
        if (limit != null) {
            this.writeStringAttribute(ChaiConstant.ATTR_LDAP_LOGIN_GRACE_REMAINING, limit);
        }
    }

    public final Date readDateAttribute(final String attributeName)
            throws ChaiUnavailableException, ChaiOperationException
    {
        final String lastLoginTimeStr = this.readStringAttribute(attributeName);
        if (lastLoginTimeStr != null) {
            return EdirEntries.convertZuluToDate(lastLoginTimeStr);
        }
        return null;
    }


    public final void addGroupMembership(final ChaiGroup theGroup)
            throws ChaiOperationException, ChaiUnavailableException
    {
        EdirEntries.writeGroupMembership(this, theGroup);
    }

    public void removeGroupMembership(final ChaiGroup theGroup)
            throws ChaiOperationException, ChaiUnavailableException
    {
        EdirEntries.removeGroupMembership(this, theGroup);
    }

    public final String readPassword()
            throws ChaiUnavailableException, ChaiOperationException
    {
        final boolean useNmasSetting = this.getChaiProvider().getChaiConfiguration().getBooleanSetting(ChaiSetting.EDIRECTORY_ENABLE_NMAS);
        if (!useNmasSetting) {
            throw new UnsupportedOperationException("readPassword() is notsupported with non-nmas ChaiConfiguration.securityMode()");
        }

        final GetPwdRequest request = new GetPwdRequest("", this.getEntryDN());
        final ExtendedResponse response;
        response = getChaiProvider().extendedOperation(request);
        if (response != null) {
            final GetPwdResponse getResponse = (GetPwdResponse) response;
            final int responseCode = getResponse.getNmasRetCode();
            if (responseCode != 0) {
                LOGGER.debug("error testing nmas password: " + responseCode);
                throw new ChaiOperationException("error reading nmas password: error " + responseCode, ChaiErrorCode.UNKNOWN);
            }
            return getResponse.getPwdStr();
        }

        LOGGER.debug("unknown error retreiving password (null response)");
        throw new ChaiOperationException("unknown error retreiving password (null response)", ChaiErrorCode.UNKNOWN);
    }

    public void setPassword(final String newPassword)
            throws ChaiUnavailableException, ChaiPasswordPolicyException
    {
        final boolean useNmasSetting = this.getChaiProvider().getChaiConfiguration().getBooleanSetting(ChaiSetting.EDIRECTORY_ENABLE_NMAS);
        if (!useNmasSetting) {
            try {
                writeStringAttribute(ATTR_PASSWORD, newPassword);
            } catch (ChaiOperationException e) {
                throw ChaiPasswordPolicyException.forErrorMessage(e.getMessage());
            }
        } else {
            final SetPwdRequest request = new SetPwdRequest();
            request.setData(newPassword);
            request.setObjectDN(this.getEntryDN());
            final ExtendedResponse response;
            try {
                response = getChaiProvider().extendedOperation(request);
            } catch (ChaiOperationException e) {
                throw ChaiPasswordPolicyException.forErrorMessage(e.getMessage());
            }
            if (response != null) {
                final SetPwdResponse setResponse = (SetPwdResponse) response;
                final int responseCode = setResponse.getNmasRetCode();
                if (responseCode != 0) {
                    LOGGER.debug("error setting nmas password: " + responseCode);
                    throw ChaiPasswordPolicyException.forErrorCode(responseCode);
                }
            }
        }
    }

    public boolean isPasswordExpired()
            throws ChaiUnavailableException, ChaiOperationException
    {
        final String[] attrsToRead = new String[] {
                ChaiConstant.ATTR_LDAP_LOGIN_GRACE_LIMIT,
                ChaiConstant.ATTR_LDAP_LOGIN_GRACE_REMAINING,
                ATTR_PASSWORD_EXPIRE_TIME
        };

        final Properties userAttrs = readStringAttributes(new HashSet<String>(Arrays.asList(attrsToRead)));

        //check the limits and see if they are different, if they are different, then we're expired for sure.
        final int limit = StringHelper.convertStrToInt(userAttrs.getProperty(ChaiConstant.ATTR_LDAP_LOGIN_GRACE_LIMIT),0);
        final int current = StringHelper.convertStrToInt(userAttrs.getProperty(ChaiConstant.ATTR_LDAP_LOGIN_GRACE_REMAINING),0);
        final int remaining = limit - current;
        if (current != limit) {
            LOGGER.debug("user " + this.getEntryDN() + " has " + remaining + " grace logins remaining, marking as expired");
            return true;
        }

        //check the limits and see if they are different, if they are different, then we're expired for sure.
        final String petExpireString = userAttrs.getProperty(ATTR_PASSWORD_EXPIRE_TIME,"");
        if (petExpireString.length() > 0) {
            final Date expireDate = EdirEntries.convertZuluToDate(petExpireString);
            final long diff = expireDate.getTime() - System.currentTimeMillis();
            if (diff <= 0) {
                LOGGER.debug("user " + getEntryDN() + " password expired " + diff + " seconds ago (" + expireDate + ", marking as expired");
                return true;
            }
        }

        return false;
    }

    public ChallengeSet readAssignedChallengeSet()
            throws ChaiUnavailableException, ChaiOperationException
    {
        try {
            return CrFactory.readAssignedChallengeSet(this);
        } catch (ChaiValidationException e) {
            LOGGER.info("Validation error reading Chai Assigned associations sets " + e.getValidationError().getDebugDescription());
            return null;
        }
    }

    public final String readGivenName()
            throws ChaiOperationException, ChaiUnavailableException
    {
        return this.readStringAttribute(ATTR_GIVEN_NAME);
    }

    public final Date readLastLoginTime()
            throws ChaiOperationException, ChaiUnavailableException
    {
        return this.readDateAttribute(ATTR_LAST_LOGIN);
    }

    public ResponseSet readResponseSet()
            throws ChaiUnavailableException, ChaiOperationException
    {
        try {
            return CrFactory.readResponseSet(this);
        } catch (ChaiValidationException e) {
            LOGGER.info("Validation error reading Chai Assigned associations sets " + e.getValidationError().getDebugDescription());
            return null;
        }
    }

    public final void changePassword(final String oldPassword, final String newPassword)
            throws ChaiUnavailableException, ChaiPasswordPolicyException
    {
        final boolean useNmasSetting = this.getChaiProvider().getChaiConfiguration().getBooleanSetting(ChaiSetting.EDIRECTORY_ENABLE_NMAS);
        if (!useNmasSetting) {
            try {
                replaceAttribute(ATTR_PASSWORD, oldPassword, newPassword);
            } catch (ChaiOperationException e) {
                throw ChaiPasswordPolicyException.forErrorMessage(e.getMessage());
            }
        } else {
            final ChangePwdRequest request = new ChangePwdRequest();
            request.setNewPwd(newPassword);
            request.setObjectDN(this.getEntryDN());
            request.setOldPwd(oldPassword);
            final ExtendedResponse response;
            try {
                response = getChaiProvider().extendedOperation(request);
            } catch (ChaiOperationException e) {
                throw ChaiPasswordPolicyException.forErrorMessage(e.getMessage());
            }
            if (response != null) {
                final ChangePwdResponse changeResponse = (ChangePwdResponse) response;
                final int responseCode = changeResponse.getNmasRetCode();
                if (responseCode != 0) {
                    LOGGER.debug("error changing nmas password: " + responseCode);
                    throw ChaiPasswordPolicyException.forErrorCode(responseCode);
                }
            }
        }
    }

    public void expirePassword()
            throws ChaiOperationException, ChaiUnavailableException
    {
        this.writeStringAttribute(ChaiConstant.ATTR_LDAP_LOGIN_INTRUDER_RESET_TIME, "19700101010101Z");
    }

    public boolean isLocked()
            throws ChaiOperationException, ChaiUnavailableException
    {
        return readBooleanAttribute("lockedByIntruder");
    }

    public Date readPasswordExpirationDate() throws ChaiUnavailableException, ChaiOperationException {
        Date returnDate = readDateAttribute(ATTR_PASSWORD_EXPIRE_TIME);
        if (returnDate == null) {
            if (isPasswordExpired()) {
                returnDate = new Date();
            }
        }
        return returnDate;
    }
}