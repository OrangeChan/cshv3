// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.api.command.user.iso;

import java.util.List;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.GuestOSResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ProjectResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.log4j.Logger;

import com.cloud.exception.ResourceAllocationException;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.UserContext;

@APICommand(name = "registerIso", responseObject=TemplateResponse.class, description="Registers an existing ISO into the CloudStack Cloud.")
public class RegisterIsoCmd extends BaseCmd {
    public static final Logger s_logger = Logger.getLogger(RegisterIsoCmd.class.getName());

    private static final String s_name = "registerisoresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name=ApiConstants.BOOTABLE, type=CommandType.BOOLEAN, description="true if this ISO is bootable. If not passed explicitly its assumed to be true")
    private Boolean bootable;

    @Parameter(name=ApiConstants.DISPLAY_TEXT, type=CommandType.STRING, required=true, description="the display text of the ISO. This is usually used for display purposes.", length=4096)
    private String displayText;

    @Parameter(name=ApiConstants.IS_FEATURED, type=CommandType.BOOLEAN, description="true if you want this ISO to be featured")
    private Boolean featured;

    @Parameter(name=ApiConstants.IS_PUBLIC, type=CommandType.BOOLEAN, description="true if you want to register the ISO to be publicly available to all users, false otherwise.")
    private Boolean publicIso;

    @Parameter(name=ApiConstants.IS_EXTRACTABLE, type=CommandType.BOOLEAN, description="true if the iso or its derivatives are extractable; default is false")
    private Boolean extractable;

    @Parameter(name=ApiConstants.NAME, type=CommandType.STRING, required=true, description="the name of the ISO")
    private String isoName;

    @Parameter(name=ApiConstants.OS_TYPE_ID, type=CommandType.UUID, entityType = GuestOSResponse.class,
            description="the ID of the OS Type that best represents the OS of this ISO. If the iso is bootable this parameter needs to be passed")
    private Long osTypeId;

    @Parameter(name=ApiConstants.URL, type=CommandType.STRING, required=true, description="the URL to where the ISO is currently being hosted")
    private String url;

    @Parameter(name=ApiConstants.ZONE_ID, type=CommandType.UUID, entityType = ZoneResponse.class,
            required=true, description="the ID of the zone you wish to register the ISO to.")
    private Long zoneId;

    @Parameter(name=ApiConstants.DOMAIN_ID, type=CommandType.UUID, entityType = DomainResponse.class,
            description="an optional domainId. If the account parameter is used, domainId must also be used.")
    private Long domainId;

    @Parameter(name=ApiConstants.ACCOUNT, type=CommandType.STRING, description="an optional account name. Must be used with domainId.")
    private String accountName;

    @Parameter(name=ApiConstants.CHECKSUM, type=CommandType.STRING, description="the MD5 checksum value of this ISO")
    private String checksum;

    @Parameter(name=ApiConstants.PROJECT_ID, type=CommandType.UUID, entityType = ProjectResponse.class,
            description="Register iso for the project")
    private Long projectId;

    @Parameter(name=ApiConstants.IMAGE_STORE_UUID, type=CommandType.STRING,
            description="Image store uuid")
    private String imageStoreUuid;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Boolean isBootable() {
        return bootable;
    }

    public String getDisplayText() {
        return displayText;
    }

    public Boolean isFeatured() {
        return featured;
    }

    public Boolean isPublic() {
        return publicIso;
    }

    public Boolean isExtractable() {
        return extractable;
    }

    public String getIsoName() {
        return isoName;
    }

    public Long getOsTypeId() {
        return osTypeId;
    }

    public String getUrl() {
        return url;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public Long getDomainId() {
        return domainId;
    }

    public String getAccountName() {
        return accountName;
    }

    public String getChecksum() {
        return checksum;
    }

    public String getImageStoreUuid() {
        return this.imageStoreUuid;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        Long accountId = finalyzeAccountId(accountName, domainId, projectId, true);
        if (accountId == null) {
            return UserContext.current().getCaller().getId();
        }

        return accountId;
    }

    @Override
    public void execute() throws ResourceAllocationException{
        VirtualMachineTemplate template = _templateService.registerIso(this);
        if (template != null) {
            ListResponse<TemplateResponse> response = new ListResponse<TemplateResponse>();
            List<TemplateResponse> templateResponses = _responseGenerator.createIsoResponses(template, zoneId, false);
            response.setResponses(templateResponses);
            response.setResponseName(getCommandName());
            this.setResponseObject(response);
        } else {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to register iso");
        }

    }
}
