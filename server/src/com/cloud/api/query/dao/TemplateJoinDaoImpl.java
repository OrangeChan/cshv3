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
package com.cloud.api.query.dao;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ejb.Local;
import javax.inject.Inject;

import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.api.response.TemplateZoneResponse;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateState;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.ApiDBUtils;
import com.cloud.api.ApiResponseHelper;
import com.cloud.api.query.vo.ResourceTagJoinVO;
import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.storage.VMTemplateHostVO;
import com.cloud.storage.Storage.TemplateType;
import com.cloud.storage.VMTemplateStorageResourceAssoc.Status;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.UserContext;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;


@Component
@Local(value={TemplateJoinDao.class})
public class TemplateJoinDaoImpl extends GenericDaoBase<TemplateJoinVO, Long> implements TemplateJoinDao {



    public static final Logger s_logger = Logger.getLogger(TemplateJoinDaoImpl.class);

    @Inject
    private ConfigurationDao  _configDao;

    private final SearchBuilder<TemplateJoinVO> tmpltSearch;

    private final SearchBuilder<TemplateJoinVO> tmpltIdSearch;

    private final SearchBuilder<TemplateJoinVO> tmpltZoneSearch;

    private final SearchBuilder<TemplateJoinVO> activeTmpltSearch;


    protected TemplateJoinDaoImpl() {

        tmpltSearch = createSearchBuilder();
        tmpltSearch.and("idIN", tmpltSearch.entity().getId(), SearchCriteria.Op.IN);
        tmpltSearch.done();

        tmpltIdSearch = createSearchBuilder();
        tmpltIdSearch.and("id", tmpltIdSearch.entity().getId(), SearchCriteria.Op.EQ);
        tmpltIdSearch.done();

        tmpltZoneSearch = createSearchBuilder();
        tmpltZoneSearch.and("id", tmpltZoneSearch.entity().getId(), SearchCriteria.Op.EQ);
        tmpltZoneSearch.and("dataCenterId", tmpltZoneSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        tmpltZoneSearch.and("state", tmpltZoneSearch.entity().getState(), SearchCriteria.Op.EQ);
        tmpltZoneSearch.done();

        activeTmpltSearch = createSearchBuilder();
        activeTmpltSearch.and("store_id", activeTmpltSearch.entity().getDataStoreId(), SearchCriteria.Op.EQ);
        activeTmpltSearch.and("type", activeTmpltSearch.entity().getTemplateType(), SearchCriteria.Op.EQ);
        activeTmpltSearch.done();

        // select distinct pair (template_id, zone_id)
        this._count = "select count(distinct id) from template_view WHERE ";
    }




    @Override
    public TemplateResponse newTemplateResponse(TemplateJoinVO template) {
        TemplateResponse templateResponse = new TemplateResponse();
        templateResponse.setId(template.getUuid());
        templateResponse.setName(template.getName());
        templateResponse.setDisplayText(template.getDisplayText());
        templateResponse.setPublic(template.isPublicTemplate());
        templateResponse.setCreated(template.getCreatedOnStore());
        templateResponse.setReady(template.getState() == ObjectInDataStoreStateMachine.State.Ready);
        templateResponse.setFeatured(template.isFeatured());
        templateResponse.setExtractable(template.isExtractable() && !(template.getTemplateType() == TemplateType.SYSTEM));
        templateResponse.setPasswordEnabled(template.isEnablePassword());
        templateResponse.setSshKeyEnabled(template.isEnableSshKey());
        templateResponse.setCrossZones(template.isCrossZones());
        templateResponse.setFormat(template.getFormat());
        if (template.getTemplateType() != null) {
            templateResponse.setTemplateType(template.getTemplateType().toString());
        }

        templateResponse.setHypervisor(template.getHypervisorType().toString());


        templateResponse.setOsTypeId(template.getGuestOSUuid());
        templateResponse.setOsTypeName(template.getGuestOSName());

        // populate owner.
        ApiResponseHelper.populateOwner(templateResponse, template);

        // populate domain
        templateResponse.setDomainId(template.getDomainUuid());
        templateResponse.setDomainName(template.getDomainName());



        boolean isAdmin = false;
        Account caller = UserContext.current().getCaller();
        if ((caller == null) || BaseCmd.isAdmin(caller.getType())) {
            isAdmin = true;
        }

        // If the user is an Admin, add the template download status
        if (isAdmin || caller.getId() == template.getAccountId()) {
            // add download status
            if (template.getDownloadState() != Status.DOWNLOADED) {
                String templateStatus = "Processing";
                if (template.getDownloadState() == VMTemplateHostVO.Status.DOWNLOAD_IN_PROGRESS) {
                    if (template.getDownloadPercent() == 100) {
                        templateStatus = "Installing Template";
                    } else {
                        templateStatus = template.getDownloadPercent() + "% Downloaded";
                    }
                } else {
                    templateStatus = template.getErrorString();
                }
                templateResponse.setStatus(templateStatus);
            } else if (template.getDownloadState() == VMTemplateHostVO.Status.DOWNLOADED) {
                templateResponse.setStatus("Download Complete");
            } else {
                templateResponse.setStatus("Successfully Installed");
            }
        }

        Long templateSize = template.getSize();
        if (templateSize > 0) {
            templateResponse.setSize(templateSize);
        }

        templateResponse.setChecksum(template.getChecksum());
        if (template.getSourceTemplateId() != null) {
            templateResponse.setSourceTemplateId(template.getSourceTemplateUuid());
        }
        templateResponse.setTemplateTag(template.getTemplateTag());

        // set template zone information
        if (template.getDataCenterId() > 0 ){
            TemplateZoneResponse tmplZoneResp = new TemplateZoneResponse(template.getDataCenterUuid(), template.getDataCenterName());
            templateResponse.addZone(tmplZoneResp);
            // set the first found associated zone directly in TemplateResponse
            templateResponse.setZoneId(template.getDataCenterUuid());
            templateResponse.setZoneName(template.getDataCenterName());
        }

        // set details map
        if (template.getDetailName() != null){
            Map<String, String> details = new HashMap<String, String>();
            details.put(template.getDetailName(), template.getDetailValue());
            templateResponse.setDetails(details);
        }

        // update tag information
        long tag_id = template.getTagId();
        if (tag_id > 0) {
            ResourceTagJoinVO vtag = ApiDBUtils.findResourceTagViewById(tag_id);
            if ( vtag != null ){
                templateResponse.addTag(ApiDBUtils.newResourceTagResponse(vtag, false));
            }
        }


        templateResponse.setObjectName("template");
        return templateResponse;
    }


    //TODO: This is to keep compatibility with 4.1 API, where updateTemplateCmd and updateIsoCmd will return a simpler TemplateResponse
    // compared to listTemplates and listIsos.
    @Override
    public TemplateResponse newUpdateResponse(TemplateJoinVO result) {
        TemplateResponse response = new TemplateResponse();
        response.setId(result.getUuid());
        response.setName(result.getName());
        response.setDisplayText(result.getDisplayText());
        response.setPublic(result.isPublicTemplate());
        response.setCreated(result.getCreated());
        response.setFormat(result.getFormat());
        response.setOsTypeId(result.getGuestOSUuid());
        response.setOsTypeName(result.getGuestOSName());
        response.setBootable(result.isBootable());
        response.setHypervisor(result.getHypervisorType().toString());

        // populate owner.
        ApiResponseHelper.populateOwner(response, result);

        // populate domain
        response.setDomainId(result.getDomainUuid());
        response.setDomainName(result.getDomainName());

        // set details map
        if (result.getDetailName() != null) {
            Map<String, String> details = new HashMap<String, String>();
            details.put(result.getDetailName(), result.getDetailValue());
            response.setDetails(details);
        }

        // update tag information
        long tag_id = result.getTagId();
        if (tag_id > 0) {
            ResourceTagJoinVO vtag = ApiDBUtils.findResourceTagViewById(tag_id);
            if (vtag != null) {
                response.addTag(ApiDBUtils.newResourceTagResponse(vtag, false));
            }
        }

        response.setObjectName("iso");
        return response;
    }




    @Override
    public TemplateResponse setTemplateResponse(TemplateResponse templateResponse, TemplateJoinVO template) {

        // update template zone information
        if (template.getDataCenterId() > 0 ){
            TemplateZoneResponse tmplZoneResp = new TemplateZoneResponse(template.getDataCenterUuid(), template.getDataCenterName());
            templateResponse.addZone(tmplZoneResp);
            if (templateResponse.getZoneId() == null) {
                // set the first found associated zone directly in
                // TemplateResponse
                templateResponse.setZoneId(template.getDataCenterUuid());
                templateResponse.setZoneName(template.getDataCenterName());
            }
        }

        // update details map
        if (template.getDetailName() != null){
            Map<String, String> details = templateResponse.getDetails();
            if ( details == null ){
                details = new HashMap<String, String>();
            }
            details.put(template.getDetailName(), template.getDetailValue());
            templateResponse.setDetails(details);
        }

        // update tag information
        long tag_id = template.getTagId();
        if (tag_id > 0) {
            ResourceTagJoinVO vtag = ApiDBUtils.findResourceTagViewById(tag_id);
            if ( vtag != null ){
                templateResponse.addTag(ApiDBUtils.newResourceTagResponse(vtag, false));
            }
        }

        return templateResponse;
    }


    @Override
    public TemplateResponse newIsoResponse(TemplateJoinVO iso) {

        TemplateResponse isoResponse = new TemplateResponse();
        isoResponse.setId(iso.getUuid());
        isoResponse.setName(iso.getName());
        isoResponse.setDisplayText(iso.getDisplayText());
        isoResponse.setPublic(iso.isPublicTemplate());
        isoResponse.setExtractable(iso.isExtractable() && !(iso.getTemplateType() == TemplateType.PERHOST));
        isoResponse.setCreated(iso.getCreatedOnStore());
        if ( iso.getTemplateType() == TemplateType.PERHOST ){
            // for xs-tools.iso and vmware-tools.iso, we didn't download, but is ready to use.
            isoResponse.setReady(true);
        }
        else{
            isoResponse.setReady(iso.getState() == ObjectInDataStoreStateMachine.State.Ready);
        }
        isoResponse.setBootable(iso.isBootable());
        isoResponse.setFeatured(iso.isFeatured());
        isoResponse.setCrossZones(iso.isCrossZones());
        isoResponse.setPublic(iso.isPublicTemplate());
        isoResponse.setChecksum(iso.getChecksum());

        isoResponse.setOsTypeId(iso.getGuestOSUuid());
        isoResponse.setOsTypeName(iso.getGuestOSName());

        // populate owner.
        ApiResponseHelper.populateOwner(isoResponse, iso);

        // populate domain
        isoResponse.setDomainId(iso.getDomainUuid());
        isoResponse.setDomainName(iso.getDomainName());

        // set template zone information
        if (iso.getDataCenterId() > 0 ){
            TemplateZoneResponse tmplZoneResp = new TemplateZoneResponse(iso.getDataCenterUuid(), iso.getDataCenterName());
            isoResponse.addZone(tmplZoneResp);
            // set the first found associated zone directly in TemplateResponse
            isoResponse.setZoneId(iso.getDataCenterUuid());
            isoResponse.setZoneName(iso.getDataCenterName());
        }


        Account caller = UserContext.current().getCaller();
        boolean isAdmin = false;
        if ((caller == null) || BaseCmd.isAdmin(caller.getType())) {
            isAdmin = true;
        }


        // If the user is an admin, add the template download status
        if (isAdmin || caller.getId() == iso.getAccountId()) {
            // add download status
            if (iso.getDownloadState() != Status.DOWNLOADED) {
                String isoStatus = "Processing";
                if (iso.getDownloadState() == VMTemplateHostVO.Status.DOWNLOADED) {
                    isoStatus = "Download Complete";
                } else if (iso.getDownloadState() == VMTemplateHostVO.Status.DOWNLOAD_IN_PROGRESS) {
                    if (iso.getDownloadPercent() == 100) {
                        isoStatus = "Installing ISO";
                    } else {
                        isoStatus = iso.getDownloadPercent() + "% Downloaded";
                    }
                } else {
                    isoStatus = iso.getErrorString();
                }
                isoResponse.setStatus(isoStatus);
            } else {
                isoResponse.setStatus("Successfully Installed");
            }
        }

        Long isoSize = iso.getSize();
        if (isoSize > 0) {
            isoResponse.setSize(isoSize);
        }

        // update tag information
        long tag_id = iso.getTagId();
        if (tag_id > 0) {
            ResourceTagJoinVO vtag = ApiDBUtils.findResourceTagViewById(tag_id);
            if ( vtag != null ){
                isoResponse.addTag(ApiDBUtils.newResourceTagResponse(vtag, false));
            }
        }

        isoResponse.setObjectName("iso");
        return isoResponse;

    }

    @Override
    public List<TemplateJoinVO> newTemplateView(VirtualMachineTemplate template) {
        SearchCriteria<TemplateJoinVO> sc = tmpltIdSearch.create();
        sc.setParameters("id", template.getId());
        return searchIncludingRemoved(sc, null, null, false);
    }

    @Override
    public List<TemplateJoinVO> newTemplateView(VirtualMachineTemplate template, long zoneId, boolean readyOnly) {
        SearchCriteria<TemplateJoinVO> sc = tmpltZoneSearch.create();
        sc.setParameters("id", template.getId());
        sc.setParameters("dataCenterId", zoneId);
        if ( readyOnly ){
            sc.setParameters("state", TemplateState.Ready);
        }
        return searchIncludingRemoved(sc, null, null, false);
    }



    @Override
    public List<TemplateJoinVO> searchByIds(Long... tmplIds) {
        // set detail batch query size
        int DETAILS_BATCH_SIZE = 2000;
        String batchCfg = _configDao.getValue("detail.batch.query.size");
        if ( batchCfg != null ){
            DETAILS_BATCH_SIZE = Integer.parseInt(batchCfg);
        }
        // query details by batches
        List<TemplateJoinVO> uvList = new ArrayList<TemplateJoinVO>();
        // query details by batches
        int curr_index = 0;
        if ( tmplIds.length > DETAILS_BATCH_SIZE ){
            while ( (curr_index + DETAILS_BATCH_SIZE ) <= tmplIds.length ) {
                Long[] ids = new Long[DETAILS_BATCH_SIZE];
                for (int k = 0, j = curr_index; j < curr_index + DETAILS_BATCH_SIZE; j++, k++) {
                    ids[k] = tmplIds[j];
                }
                SearchCriteria<TemplateJoinVO> sc = tmpltSearch.create();
                sc.setParameters("idIN", ids);
                List<TemplateJoinVO> vms = searchIncludingRemoved(sc, null, null, false);
                if (vms != null) {
                    uvList.addAll(vms);
                }
                curr_index += DETAILS_BATCH_SIZE;
            }
        }
        if (curr_index < tmplIds.length) {
            int batch_size = (tmplIds.length - curr_index);
            // set the ids value
            Long[] ids = new Long[batch_size];
            for (int k = 0, j = curr_index; j < curr_index + batch_size; j++, k++) {
                ids[k] = tmplIds[j];
            }
            SearchCriteria<TemplateJoinVO> sc = tmpltSearch.create();
            sc.setParameters("idIN", ids);
            List<TemplateJoinVO> vms = searchIncludingRemoved(sc, null, null, false);
            if (vms != null) {
                uvList.addAll(vms);
            }
        }
        return uvList;
    }




    @Override
    public List<TemplateJoinVO> listActiveTemplates(long storeId) {
        SearchCriteria<TemplateJoinVO> sc = activeTmpltSearch.create();
        sc.setParameters("store_id", storeId);
        sc.setParameters("type", TemplateType.USER);
        return searchIncludingRemoved(sc, null, null, false);
    }



}
