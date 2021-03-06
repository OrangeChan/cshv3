/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.volume;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.engine.cloud.entity.api.VolumeEntity;
import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.CreateCmdResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataMotionService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.Event;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.framework.async.AsyncCallbackDispatcher;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.framework.async.AsyncRpcConext;
import org.apache.cloudstack.storage.command.CommandResult;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.datastore.DataObjectManager;
import org.apache.cloudstack.storage.datastore.ObjectInDataStoreManager;
import org.apache.cloudstack.storage.datastore.PrimaryDataStore;
import org.apache.cloudstack.storage.datastore.PrimaryDataStoreProviderManager;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.ListVolumeAnswer;
import com.cloud.agent.api.storage.ListVolumeCommand;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.alert.AlertManager;
import com.cloud.configuration.Config;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.host.Host;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.StoragePool;
import com.cloud.storage.VMTemplateStorageResourceAssoc;
import com.cloud.storage.VMTemplateStorageResourceAssoc.Status;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.snapshot.SnapshotManager;
import com.cloud.storage.template.TemplateProp;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.db.DB;

@Component
public class VolumeServiceImpl implements VolumeService {
    private static final Logger s_logger = Logger.getLogger(VolumeServiceImpl.class);
    @Inject
    VolumeDao volDao;
    @Inject
    PrimaryDataStoreProviderManager dataStoreMgr;
    @Inject
    ObjectInDataStoreManager objectInDataStoreMgr;
    @Inject
    DataObjectManager dataObjectMgr;
    @Inject
    DataMotionService motionSrv;
    @Inject
    VolumeDataFactory volFactory;
    @Inject
    SnapshotManager snapshotMgr;
    @Inject
    ResourceLimitService _resourceLimitMgr;
    @Inject
    AccountManager _accountMgr;
    @Inject
    AlertManager _alertMgr;
    @Inject
    ConfigurationDao configDao;
    @Inject
    VolumeDataStoreDao _volumeStoreDao;
    @Inject
    VolumeDao _volumeDao;
    @Inject
    EndPointSelector _epSelector;

    public VolumeServiceImpl() {
    }

    private class CreateVolumeContext<T> extends AsyncRpcConext<T> {

        private final DataObject volume;
        private final AsyncCallFuture<VolumeApiResult> future;

        public CreateVolumeContext(AsyncCompletionCallback<T> callback, DataObject volume,
                AsyncCallFuture<VolumeApiResult> future) {
            super(callback);
            this.volume = volume;
            this.future = future;
        }

        public DataObject getVolume() {
            return this.volume;
        }

        public AsyncCallFuture<VolumeApiResult> getFuture() {
            return this.future;
        }

    }

    @Override
    public AsyncCallFuture<VolumeApiResult> createVolumeAsync(VolumeInfo volume, DataStore dataStore) {
        AsyncCallFuture<VolumeApiResult> future = new AsyncCallFuture<VolumeApiResult>();
        DataObject volumeOnStore = dataStore.create(volume);
        volumeOnStore.processEvent(Event.CreateOnlyRequested);

        CreateVolumeContext<VolumeApiResult> context = new CreateVolumeContext<VolumeApiResult>(null, volumeOnStore,
                future);
        AsyncCallbackDispatcher<VolumeServiceImpl, CreateCmdResult> caller = AsyncCallbackDispatcher.create(this);
        caller.setCallback(caller.getTarget().createVolumeCallback(null, null)).setContext(context);

        dataStore.getDriver().createAsync(volumeOnStore, caller);
        return future;
    }

    protected Void createVolumeCallback(AsyncCallbackDispatcher<VolumeServiceImpl, CreateCmdResult> callback,
            CreateVolumeContext<VolumeApiResult> context) {
        CreateCmdResult result = callback.getResult();
        DataObject vo = context.getVolume();
        String errMsg = null;
        if (result.isSuccess()) {
            vo.processEvent(Event.OperationSuccessed, result.getAnswer());
        } else {
            vo.processEvent(Event.OperationFailed);
            errMsg = result.getResult();
        }
        VolumeApiResult volResult = new VolumeApiResult((VolumeObject) vo);
        if (errMsg != null) {
            volResult.setResult(errMsg);
        }
        context.getFuture().complete(volResult);
        return null;
    }

    private class DeleteVolumeContext<T> extends AsyncRpcConext<T> {
        private final VolumeObject volume;
        private final AsyncCallFuture<VolumeApiResult> future;

        public DeleteVolumeContext(AsyncCompletionCallback<T> callback, VolumeObject volume,
                AsyncCallFuture<VolumeApiResult> future) {
            super(callback);
            this.volume = volume;
            this.future = future;
        }

        public VolumeObject getVolume() {
            return this.volume;
        }

        public AsyncCallFuture<VolumeApiResult> getFuture() {
            return this.future;
        }
    }

    @DB
    @Override
    public AsyncCallFuture<VolumeApiResult> expungeVolumeAsync(VolumeInfo volume) {
        AsyncCallFuture<VolumeApiResult> future = new AsyncCallFuture<VolumeApiResult>();
        VolumeApiResult result = new VolumeApiResult(volume);
        if (volume.getDataStore() == null) {
            volDao.remove(volume.getId());
            future.complete(result);
            return future;
        }

        // Find out if the volume is at state of download_in_progress on secondary storage
        VolumeDataStoreVO volumeStore = _volumeStoreDao.findByVolume(volume.getId());
        if (volumeStore != null) {
            if (volumeStore.getDownloadState() == VMTemplateStorageResourceAssoc.Status.DOWNLOAD_IN_PROGRESS) {
                s_logger.debug("Volume: " + volume.getName() + " is currently being uploaded; cant' delete it.");
                future.complete(result);
                return future;
            }
        }

        VolumeVO vol = volDao.findById(volume.getId());

        String volumePath = vol.getPath();
        Long poolId = vol.getPoolId();
        if (poolId == null || volumePath == null || volumePath.trim().isEmpty()) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Marking volume that was never created as destroyed: " + vol);
            }
            volDao.remove(vol.getId());
            future.complete(result);
            return future;
        }
        VolumeObject vo = (VolumeObject) volume;

        volume.processEvent(Event.ExpungeRequested);

        DeleteVolumeContext<VolumeApiResult> context = new DeleteVolumeContext<VolumeApiResult>(null, vo, future);
        AsyncCallbackDispatcher<VolumeServiceImpl, CommandResult> caller = AsyncCallbackDispatcher.create(this);
        caller.setCallback(caller.getTarget().deleteVolumeCallback(null, null)).setContext(context);

        volume.getDataStore().getDriver().deleteAsync(volume, caller);
        return future;
    }

    public Void deleteVolumeCallback(AsyncCallbackDispatcher<VolumeServiceImpl, CommandResult> callback,
            DeleteVolumeContext<VolumeApiResult> context) {
        CommandResult result = callback.getResult();
        VolumeObject vo = context.getVolume();
        VolumeApiResult apiResult = new VolumeApiResult(vo);
        if (result.isSuccess()) {
            vo.processEvent(Event.OperationSuccessed);
            volDao.remove(vo.getId());
        } else {
            vo.processEvent(Event.OperationFailed);
            apiResult.setResult(result.getResult());
        }
        context.getFuture().complete(apiResult);
        return null;
    }

    @Override
    public boolean cloneVolume(long volumeId, long baseVolId) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public VolumeEntity getVolumeEntity(long volumeId) {
        return null;
    }

    class CreateBaseImageContext<T> extends AsyncRpcConext<T> {
        private final VolumeInfo volume;
        private final PrimaryDataStore dataStore;
        private final TemplateInfo srcTemplate;
        private final AsyncCallFuture<VolumeApiResult> future;
        final DataObject destObj;

        public CreateBaseImageContext(AsyncCompletionCallback<T> callback, VolumeInfo volume,
                PrimaryDataStore datastore, TemplateInfo srcTemplate, AsyncCallFuture<VolumeApiResult> future,
                DataObject destObj) {
            super(callback);
            this.volume = volume;
            this.dataStore = datastore;
            this.future = future;
            this.srcTemplate = srcTemplate;
            this.destObj = destObj;
        }

        public VolumeInfo getVolume() {
            return this.volume;
        }

        public PrimaryDataStore getDataStore() {
            return this.dataStore;
        }

        public TemplateInfo getSrcTemplate() {
            return this.srcTemplate;
        }

        public AsyncCallFuture<VolumeApiResult> getFuture() {
            return this.future;
        }

    }

    private TemplateInfo waitForTemplateDownloaded(PrimaryDataStore store, TemplateInfo template) {
        int storagePoolMaxWaitSeconds = NumbersUtil.parseInt(
                configDao.getValue(Config.StoragePoolMaxWaitSeconds.key()), 3600);
        int sleepTime = 120;
        int tries = storagePoolMaxWaitSeconds / sleepTime;
        while (tries > 0) {
            TemplateInfo tmpl = store.getTemplate(template.getId());
            if (tmpl != null) {
                return tmpl;
            }
            try {
                Thread.sleep(sleepTime * 1000);
            } catch (InterruptedException e) {
                s_logger.debug("waiting for template download been interrupted: " + e.toString());
            }
            tries--;
        }
        return null;
    }

    @DB
    protected void createBaseImageAsync(VolumeInfo volume, PrimaryDataStore dataStore, TemplateInfo template,
            AsyncCallFuture<VolumeApiResult> future) {

        DataObject templateOnPrimaryStoreObj = dataStore.create(template);
        CreateBaseImageContext<CreateCmdResult> context = new CreateBaseImageContext<CreateCmdResult>(null, volume,
                dataStore, template, future, templateOnPrimaryStoreObj);
        AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> caller = AsyncCallbackDispatcher.create(this);
        caller.setCallback(caller.getTarget().copyBaseImageCallback(null, null)).setContext(context);

        try {
            templateOnPrimaryStoreObj.processEvent(Event.CreateOnlyRequested);
        } catch (Exception e) {
            s_logger.info("Got exception in case of multi-thread");
            try {
                templateOnPrimaryStoreObj = waitForTemplateDownloaded(dataStore, template);
            } catch (Exception e1) {
                s_logger.debug("wait for template:" + template.getId() + " downloading finished, but failed");
                VolumeApiResult result = new VolumeApiResult(volume);
                result.setResult(e1.toString());
                future.complete(result);
                return;
            }
            if (templateOnPrimaryStoreObj == null) {
                VolumeApiResult result = new VolumeApiResult(volume);
                result.setResult("wait for template:" + template.getId() + " downloading finished, but failed");
                future.complete(result);
                return;
            } else {
                s_logger.debug("waiting for template:" + template.getId() + " downloading finished, success");
                createVolumeFromBaseImageAsync(volume, templateOnPrimaryStoreObj, dataStore, future);
                return;
            }
        }

        try {
            motionSrv.copyAsync(template, templateOnPrimaryStoreObj, caller);
        } catch (Exception e) {
            s_logger.debug("failed to create template on storage", e);
            templateOnPrimaryStoreObj.processEvent(Event.OperationFailed);
            VolumeApiResult result = new VolumeApiResult(volume);
            result.setResult(e.toString());
            future.complete(result);
        }
        return;
    }

    @DB
    protected Void copyBaseImageCallback(AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> callback,
            CreateBaseImageContext<VolumeApiResult> context) {
        CopyCommandResult result = callback.getResult();
        VolumeApiResult res = new VolumeApiResult(context.getVolume());

        AsyncCallFuture<VolumeApiResult> future = context.getFuture();
        DataObject templateOnPrimaryStoreObj = context.destObj;
        if (!result.isSuccess()) {
            templateOnPrimaryStoreObj.processEvent(Event.OperationFailed);
            res.setResult(result.getResult());
            future.complete(res);
            return null;
        }

        templateOnPrimaryStoreObj.processEvent(Event.OperationSuccessed, result.getAnswer());
        createVolumeFromBaseImageAsync(context.volume, templateOnPrimaryStoreObj, context.dataStore, future);
        return null;
    }

    private class CreateVolumeFromBaseImageContext<T> extends AsyncRpcConext<T> {
        private final DataObject vo;
        private final AsyncCallFuture<VolumeApiResult> future;
        private final DataObject templateOnStore;
        private final SnapshotInfo snapshot;

        public CreateVolumeFromBaseImageContext(AsyncCompletionCallback<T> callback, DataObject vo,
                DataStore primaryStore, DataObject templateOnStore, AsyncCallFuture<VolumeApiResult> future,
                SnapshotInfo snapshot) {
            super(callback);
            this.vo = vo;
            this.future = future;
            this.templateOnStore = templateOnStore;
            this.snapshot = snapshot;
        }

        public AsyncCallFuture<VolumeApiResult> getFuture() {
            return this.future;
        }
    }

    @DB
    protected void createVolumeFromBaseImageAsync(VolumeInfo volume, DataObject templateOnPrimaryStore,
            PrimaryDataStore pd, AsyncCallFuture<VolumeApiResult> future) {
        DataObject volumeOnPrimaryStorage = pd.create(volume);
        volumeOnPrimaryStorage.processEvent(Event.CreateOnlyRequested);

        CreateVolumeFromBaseImageContext<VolumeApiResult> context = new CreateVolumeFromBaseImageContext<VolumeApiResult>(
                null, volumeOnPrimaryStorage, pd, templateOnPrimaryStore, future, null);
        AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> caller = AsyncCallbackDispatcher.create(this);
        caller.setCallback(caller.getTarget().createVolumeFromBaseImageCallBack(null, null));
        caller.setContext(context);

        motionSrv.copyAsync(context.templateOnStore, volumeOnPrimaryStorage, caller);
        return;
    }

    @DB
    protected Void createVolumeFromBaseImageCallBack(
            AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> callback,
            CreateVolumeFromBaseImageContext<VolumeApiResult> context) {
        DataObject vo = context.vo;
        CopyCommandResult result = callback.getResult();
        VolumeApiResult volResult = new VolumeApiResult((VolumeObject) vo);

        if (result.isSuccess()) {
            vo.processEvent(Event.OperationSuccessed, result.getAnswer());
        } else {
            vo.processEvent(Event.OperationFailed);
            volResult.setResult(result.getResult());
        }

        AsyncCallFuture<VolumeApiResult> future = context.getFuture();
        future.complete(volResult);
        return null;
    }

    @DB
    @Override
    public AsyncCallFuture<VolumeApiResult> createVolumeFromTemplateAsync(VolumeInfo volume, long dataStoreId,
            TemplateInfo template) {
        PrimaryDataStore pd = dataStoreMgr.getPrimaryDataStore(dataStoreId);
        TemplateInfo templateOnPrimaryStore = pd.getTemplate(template.getId());
        AsyncCallFuture<VolumeApiResult> future = new AsyncCallFuture<VolumeApiResult>();

        if (templateOnPrimaryStore == null) {
            createBaseImageAsync(volume, pd, template, future);
            return future;
        }

        createVolumeFromBaseImageAsync(volume, templateOnPrimaryStore, pd, future);
        return future;
    }

    @Override
    @DB
    public boolean destroyVolume(long volumeId) throws ConcurrentOperationException {

        VolumeInfo vol = volFactory.getVolume(volumeId);
        vol.processEvent(Event.DestroyRequested);
        snapshotMgr.deletePoliciesForVolume(volumeId);

        vol.processEvent(Event.OperationSuccessed);

        return true;
    }

    @Override
    public AsyncCallFuture<VolumeApiResult> createVolumeFromSnapshot(VolumeInfo volume, DataStore store,
            SnapshotInfo snapshot) {
        AsyncCallFuture<VolumeApiResult> future = new AsyncCallFuture<VolumeApiResult>();

        try {
            DataObject volumeOnStore = store.create(volume);
            volumeOnStore.processEvent(Event.CreateOnlyRequested);
            snapshot.processEvent(Event.CopyingRequested);
            CreateVolumeFromBaseImageContext<VolumeApiResult> context = new CreateVolumeFromBaseImageContext<VolumeApiResult>(
                    null, volume, store, volumeOnStore, future, snapshot);
            AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> caller = AsyncCallbackDispatcher.create(this);
            caller.setCallback(caller.getTarget().createVolumeFromSnapshotCallback(null, null)).setContext(context);
            motionSrv.copyAsync(snapshot, volumeOnStore, caller);
        } catch (Exception e) {
            s_logger.debug("create volume from snapshot failed", e);
            VolumeApiResult result = new VolumeApiResult(volume);
            result.setResult(e.toString());
            future.complete(result);
        }

        return future;
    }

    protected Void createVolumeFromSnapshotCallback(
            AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> callback,
            CreateVolumeFromBaseImageContext<VolumeApiResult> context) {
        CopyCommandResult result = callback.getResult();
        VolumeInfo volume = (VolumeInfo) context.templateOnStore;
        SnapshotInfo snapshot = context.snapshot;
        VolumeApiResult apiResult = new VolumeApiResult(volume);
        Event event = null;
        if (result.isFailed()) {
            apiResult.setResult(result.getResult());
            event = Event.OperationFailed;
        } else {
            event = Event.OperationSuccessed;
        }

        try {
            if (result.isSuccess()) {
                volume.processEvent(event, result.getAnswer());
            } else {
                volume.processEvent(event);
            }
            snapshot.processEvent(event);
        } catch (Exception e) {
            s_logger.debug("create volume from snapshot failed", e);
            apiResult.setResult(e.toString());
        }

        AsyncCallFuture<VolumeApiResult> future = context.future;
        future.complete(apiResult);
        return null;
    }

    protected VolumeVO duplicateVolumeOnAnotherStorage(Volume volume, StoragePool pool) {
        Long lastPoolId = volume.getPoolId();
        VolumeVO newVol = new VolumeVO(volume);
        newVol.setPoolId(pool.getId());
        newVol.setFolder(pool.getPath());
        newVol.setPodId(pool.getPodId());
        newVol.setPoolId(pool.getId());
        newVol.setLastPoolId(lastPoolId);
        newVol.setPodId(pool.getPodId());
        return volDao.persist(newVol);
    }

    private class CopyVolumeContext<T> extends AsyncRpcConext<T> {
        final VolumeInfo srcVolume;
        final VolumeInfo destVolume;
        final AsyncCallFuture<VolumeApiResult> future;

        public CopyVolumeContext(AsyncCompletionCallback<T> callback, AsyncCallFuture<VolumeApiResult> future,
                VolumeInfo srcVolume, VolumeInfo destVolume, DataStore destStore) {
            super(callback);
            this.srcVolume = srcVolume;
            this.destVolume = destVolume;
            this.future = future;
        }

    }

    protected AsyncCallFuture<VolumeApiResult> copyVolumeFromImageToPrimary(VolumeInfo srcVolume, DataStore destStore) {
        AsyncCallFuture<VolumeApiResult> future = new AsyncCallFuture<VolumeApiResult>();
        VolumeApiResult res = new VolumeApiResult(srcVolume);
        VolumeInfo destVolume = null;
        try {
            destVolume = (VolumeInfo) destStore.create(srcVolume);
            destVolume.processEvent(Event.CopyingRequested);
            srcVolume.processEvent(Event.CopyingRequested);

            CopyVolumeContext<VolumeApiResult> context = new CopyVolumeContext<VolumeApiResult>(null, future,
                    srcVolume, destVolume, destStore);
            AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> caller = AsyncCallbackDispatcher.create(this);
            caller.setCallback(caller.getTarget().copyVolumeFromImageToPrimaryCallback(null, null)).setContext(context);

            motionSrv.copyAsync(srcVolume, destVolume, caller);
            return future;
        } catch (Exception e) {
            s_logger.error("failed to copy volume from image store", e);
            if (destVolume != null) {
                destVolume.processEvent(Event.OperationFailed);
            }

            srcVolume.processEvent(Event.OperationFailed);
            res.setResult(e.toString());
            future.complete(res);
            return future;
        }
    }

    protected Void copyVolumeFromImageToPrimaryCallback(
            AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> callback,
            CopyVolumeContext<VolumeApiResult> context) {
        VolumeInfo srcVolume = context.srcVolume;
        VolumeInfo destVolume = context.destVolume;
        CopyCommandResult result = callback.getResult();
        AsyncCallFuture<VolumeApiResult> future = context.future;
        VolumeApiResult res = new VolumeApiResult(destVolume);
        try {
            if (res.isFailed()) {
                destVolume.processEvent(Event.OperationFailed);
                srcVolume.processEvent(Event.OperationFailed);
                res.setResult(result.getResult());
                future.complete(res);
                return null;
            }

            srcVolume.processEvent(Event.OperationSuccessed);
            destVolume.processEvent(Event.OperationSuccessed, result.getAnswer());
            srcVolume.getDataStore().delete(srcVolume);
            future.complete(res);
        } catch (Exception e) {
            res.setResult(e.toString());
            future.complete(res);
        }
        return null;
    }


    protected AsyncCallFuture<VolumeApiResult> copyVolumeFromPrimaryToImage(VolumeInfo srcVolume, DataStore destStore) {
        AsyncCallFuture<VolumeApiResult> future = new AsyncCallFuture<VolumeApiResult>();
        VolumeApiResult res = new VolumeApiResult(srcVolume);
        VolumeInfo destVolume = null;
        try {
            destVolume = (VolumeInfo)destStore.create(srcVolume);
            srcVolume.processEvent(Event.MigrationRequested);    // this is just used for locking that src volume record in DB to avoid using lock
            destVolume.processEventOnly(Event.CreateOnlyRequested);

            CopyVolumeContext<VolumeApiResult> context = new CopyVolumeContext<VolumeApiResult>(null, future, srcVolume,
                    destVolume,
                    destStore);
            AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> caller = AsyncCallbackDispatcher.create(this);
            caller.setCallback(caller.getTarget().copyVolumeFromPrimaryToImageCallback(null, null))
            .setContext(context);

            motionSrv.copyAsync(srcVolume, destVolume, caller);
            return future;
        } catch (Exception e) {
            s_logger.error("failed to copy volume to image store", e);
            if (destVolume != null) {
                destVolume.getDataStore().delete(destVolume);
            }
            srcVolume.processEvent(Event.OperationFailed); // unlock source volume record
            res.setResult(e.toString());
            future.complete(res);
            return future;
        }
    }

    protected Void copyVolumeFromPrimaryToImageCallback(AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> callback, CopyVolumeContext<VolumeApiResult> context) {
        VolumeInfo srcVolume = context.srcVolume;
        VolumeInfo destVolume = context.destVolume;
        CopyCommandResult result = callback.getResult();
        AsyncCallFuture<VolumeApiResult> future = context.future;
        VolumeApiResult res = new VolumeApiResult(destVolume);
        try {
            if (res.isFailed()) {
                srcVolume.processEvent(Event.OperationFailed); // back to Ready state in Volume table
                destVolume.processEventOnly(Event.OperationFailed);
                res.setResult(result.getResult());
                future.complete(res);
            }else{
                srcVolume.processEvent(Event.OperationSuccessed); // back to Ready state in Volume table
                destVolume.processEventOnly(Event.OperationSuccessed, result.getAnswer());
                future.complete(res);
            }
        } catch (Exception e) {
            res.setResult(e.toString());
            future.complete(res);
        }
        return null;
    }


    @Override
    public AsyncCallFuture<VolumeApiResult> copyVolume(VolumeInfo srcVolume, DataStore destStore) {

        if (srcVolume.getState() == Volume.State.Uploaded) {
            return copyVolumeFromImageToPrimary(srcVolume, destStore);
        }

        if (destStore.getRole() == DataStoreRole.Image) {
            return copyVolumeFromPrimaryToImage(srcVolume, destStore);
        }

        AsyncCallFuture<VolumeApiResult> future = new AsyncCallFuture<VolumeApiResult>();
        VolumeApiResult res = new VolumeApiResult(srcVolume);
        try {
            if (!snapshotMgr.canOperateOnVolume(srcVolume)) {
                s_logger.debug("There are snapshots creating on this volume, can not move this volume");

                res.setResult("There are snapshots creating on this volume, can not move this volume");
                future.complete(res);
                return future;
            }

            VolumeVO destVol = duplicateVolumeOnAnotherStorage(srcVolume, (StoragePool) destStore);
            VolumeInfo destVolume = volFactory.getVolume(destVol.getId(), destStore);
            destVolume.processEvent(Event.MigrationRequested);
            srcVolume.processEvent(Event.MigrationRequested);

            CopyVolumeContext<VolumeApiResult> context = new CopyVolumeContext<VolumeApiResult>(null, future,
                    srcVolume, destVolume, destStore);
            AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> caller = AsyncCallbackDispatcher.create(this);
            caller.setCallback(caller.getTarget().copyVolumeCallBack(null, null)).setContext(context);
            motionSrv.copyAsync(srcVolume, destVolume, caller);
        } catch (Exception e) {
            s_logger.debug("Failed to copy volume" + e);
            res.setResult(e.toString());
            future.complete(res);
        }
        return future;
    }

    protected Void copyVolumeCallBack(AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> callback,
            CopyVolumeContext<VolumeApiResult> context) {
        VolumeInfo srcVolume = context.srcVolume;
        VolumeInfo destVolume = context.destVolume;
        CopyCommandResult result = callback.getResult();
        AsyncCallFuture<VolumeApiResult> future = context.future;
        VolumeApiResult res = new VolumeApiResult(destVolume);
        try {
            if (result.isFailed()) {
                res.setResult(result.getResult());
                destVolume.processEvent(Event.OperationFailed);
                srcVolume.processEvent(Event.OperationFailed);
                destroyVolume(destVolume.getId());
                destVolume = volFactory.getVolume(destVolume.getId());
                AsyncCallFuture<VolumeApiResult> destroyFuture = expungeVolumeAsync(destVolume);
                destroyFuture.get();
                future.complete(res);
                return null;
            }
            srcVolume.processEvent(Event.OperationSuccessed);
            destVolume.processEvent(Event.OperationSuccessed, result.getAnswer());
            destroyVolume(srcVolume.getId());
            srcVolume = volFactory.getVolume(srcVolume.getId());
            AsyncCallFuture<VolumeApiResult> destroyFuture = expungeVolumeAsync(srcVolume);
            destroyFuture.get();
            future.complete(res);
            return null;
        } catch (Exception e) {
            s_logger.debug("Failed to process copy volume callback", e);
            res.setResult(e.toString());
            future.complete(res);
        }

        return null;
    }

    private class MigrateVolumeContext<T> extends AsyncRpcConext<T> {
        final VolumeInfo srcVolume;
        final VolumeInfo destVolume;
        final AsyncCallFuture<VolumeApiResult> future;

        /**
         * @param callback
         */
        public MigrateVolumeContext(AsyncCompletionCallback<T> callback, AsyncCallFuture<VolumeApiResult> future,
                VolumeInfo srcVolume, VolumeInfo destVolume, DataStore destStore) {
            super(callback);
            this.srcVolume = srcVolume;
            this.destVolume = destVolume;
            this.future = future;
        }
    }

    @Override
    public AsyncCallFuture<VolumeApiResult> migrateVolume(VolumeInfo srcVolume, DataStore destStore) {
        AsyncCallFuture<VolumeApiResult> future = new AsyncCallFuture<VolumeApiResult>();
        VolumeApiResult res = new VolumeApiResult(srcVolume);
        try {
            if (!snapshotMgr.canOperateOnVolume(srcVolume)) {
                s_logger.debug("Snapshots are being created on this volume. This volume cannot be migrated now.");
                res.setResult("Snapshots are being created on this volume. This volume cannot be migrated now.");
                future.complete(res);
                return future;
            }

            VolumeInfo destVolume = volFactory.getVolume(srcVolume.getId(), destStore);
            srcVolume.processEvent(Event.MigrationRequested);
            MigrateVolumeContext<VolumeApiResult> context = new MigrateVolumeContext<VolumeApiResult>(null, future,
                    srcVolume, destVolume, destStore);
            AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> caller = AsyncCallbackDispatcher.create(this);
            caller.setCallback(caller.getTarget().migrateVolumeCallBack(null, null)).setContext(context);
            motionSrv.copyAsync(srcVolume, destVolume, caller);
        } catch (Exception e) {
            s_logger.debug("Failed to copy volume", e);
            res.setResult(e.toString());
            future.complete(res);
        }
        return future;
    }

    protected Void migrateVolumeCallBack(AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> callback,
            MigrateVolumeContext<VolumeApiResult> context) {
        VolumeInfo srcVolume = context.srcVolume;
        CopyCommandResult result = callback.getResult();
        AsyncCallFuture<VolumeApiResult> future = context.future;
        VolumeApiResult res = new VolumeApiResult(srcVolume);
        try {
            if (result.isFailed()) {
                res.setResult(result.getResult());
                srcVolume.processEvent(Event.OperationFailed);
                future.complete(res);
            } else {
                srcVolume.processEvent(Event.OperationSuccessed);
                future.complete(res);
            }
        } catch (Exception e) {
            s_logger.error("Failed to process copy volume callback", e);
            res.setResult(e.toString());
            future.complete(res);
        }

        return null;
    }

    private class MigrateVmWithVolumesContext<T> extends AsyncRpcConext<T> {
        final Map<VolumeInfo, DataStore> volumeToPool;
        final AsyncCallFuture<CommandResult> future;

        public MigrateVmWithVolumesContext(AsyncCompletionCallback<T> callback, AsyncCallFuture<CommandResult> future,
                Map<VolumeInfo, DataStore> volumeToPool) {
            super(callback);
            this.volumeToPool = volumeToPool;
            this.future = future;
        }
    }

    @Override
    public AsyncCallFuture<CommandResult> migrateVolumes(Map<VolumeInfo, DataStore> volumeMap, VirtualMachineTO vmTo,
            Host srcHost, Host destHost) {
        AsyncCallFuture<CommandResult> future = new AsyncCallFuture<CommandResult>();
        CommandResult res = new CommandResult();
        try {
            // Check to make sure there are no snapshot operations on a volume
            // and
            // put it in the migrating state.
            List<VolumeInfo> volumesMigrating = new ArrayList<VolumeInfo>();
            for (Map.Entry<VolumeInfo, DataStore> entry : volumeMap.entrySet()) {
                VolumeInfo volume = entry.getKey();
                if (!snapshotMgr.canOperateOnVolume(volume)) {
                    s_logger.debug("Snapshots are being created on a volume. Volumes cannot be migrated now.");
                    res.setResult("Snapshots are being created on a volume. Volumes cannot be migrated now.");
                    future.complete(res);

                    // All the volumes that are already in migrating state need
                    // to be put back in ready state.
                    for (VolumeInfo volumeMigrating : volumesMigrating) {
                        volumeMigrating.processEvent(Event.OperationFailed);
                    }
                    return future;
                } else {
                    volume.processEvent(Event.MigrationRequested);
                    volumesMigrating.add(volume);
                }
            }

            MigrateVmWithVolumesContext<CommandResult> context = new MigrateVmWithVolumesContext<CommandResult>(null,
                    future, volumeMap);
            AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> caller = AsyncCallbackDispatcher.create(this);
            caller.setCallback(caller.getTarget().migrateVmWithVolumesCallBack(null, null)).setContext(context);
            motionSrv.copyAsync(volumeMap, vmTo, srcHost, destHost, caller);

        } catch (Exception e) {
            s_logger.debug("Failed to copy volume", e);
            res.setResult(e.toString());
            future.complete(res);
        }

        return future;
    }

    protected Void migrateVmWithVolumesCallBack(AsyncCallbackDispatcher<VolumeServiceImpl, CopyCommandResult> callback,
            MigrateVmWithVolumesContext<CommandResult> context) {
        Map<VolumeInfo, DataStore> volumeToPool = context.volumeToPool;
        CopyCommandResult result = callback.getResult();
        AsyncCallFuture<CommandResult> future = context.future;
        CommandResult res = new CommandResult();
        try {
            if (result.isFailed()) {
                res.setResult(result.getResult());
                for (Map.Entry<VolumeInfo, DataStore> entry : volumeToPool.entrySet()) {
                    VolumeInfo volume = entry.getKey();
                    volume.processEvent(Event.OperationFailed);
                }
                future.complete(res);
            } else {
                for (Map.Entry<VolumeInfo, DataStore> entry : volumeToPool.entrySet()) {
                    VolumeInfo volume = entry.getKey();
                    volume.processEvent(Event.OperationSuccessed);
                }
                future.complete(res);
            }
        } catch (Exception e) {
            s_logger.error("Failed to process copy volume callback", e);
            res.setResult(e.toString());
            future.complete(res);
        }

        return null;
    }

    @Override
    public AsyncCallFuture<VolumeApiResult> registerVolume(VolumeInfo volume, DataStore store) {

        AsyncCallFuture<VolumeApiResult> future = new AsyncCallFuture<VolumeApiResult>();
        DataObject volumeOnStore = store.create(volume);

        volumeOnStore.processEvent(Event.CreateOnlyRequested);

        CreateVolumeContext<VolumeApiResult> context = new CreateVolumeContext<VolumeApiResult>(null, volumeOnStore,
                future);
        AsyncCallbackDispatcher<VolumeServiceImpl, CreateCmdResult> caller = AsyncCallbackDispatcher.create(this);
        caller.setCallback(caller.getTarget().registerVolumeCallback(null, null));
        caller.setContext(context);

        store.getDriver().createAsync(volumeOnStore, caller);
        return future;
    }

    protected Void registerVolumeCallback(AsyncCallbackDispatcher<VolumeServiceImpl, CreateCmdResult> callback,
            CreateVolumeContext<VolumeApiResult> context) {
        CreateCmdResult result = callback.getResult();
        try {
            VolumeObject vo = (VolumeObject) context.volume;
            if (result.isFailed()) {
                vo.processEvent(Event.OperationFailed);
            } else {
                vo.processEvent(Event.OperationSuccessed, result.getAnswer());
            }

            _resourceLimitMgr.incrementResourceCount(vo.getAccountId(), ResourceType.secondary_storage, vo.getSize());
            VolumeApiResult res = new VolumeApiResult(vo);
            context.future.complete(res);
            return null;
        } catch (Exception e) {
            s_logger.error("register volume failed: ", e);
            VolumeApiResult res = new VolumeApiResult(null);
            context.future.complete(res);
            return null;
        }
    }

    @Override
    public AsyncCallFuture<VolumeApiResult> resize(VolumeInfo volume) {
        AsyncCallFuture<VolumeApiResult> future = new AsyncCallFuture<VolumeApiResult>();
        VolumeApiResult result = new VolumeApiResult(volume);
        try {
            volume.processEvent(Event.ResizeRequested);
        } catch (Exception e) {
            s_logger.debug("Failed to change state to resize", e);
            result.setResult(e.toString());
            future.complete(result);
            return future;
        }
        CreateVolumeContext<VolumeApiResult> context = new CreateVolumeContext<VolumeApiResult>(null, volume, future);
        AsyncCallbackDispatcher<VolumeServiceImpl, CreateCmdResult> caller = AsyncCallbackDispatcher.create(this);
        caller.setCallback(caller.getTarget().resizeVolumeCallback(caller, context)).setContext(context);
        volume.getDataStore().getDriver().resize(volume, caller);
        return future;
    }

    protected Void resizeVolumeCallback(AsyncCallbackDispatcher<VolumeServiceImpl, CreateCmdResult> callback,
            CreateVolumeContext<VolumeApiResult> context) {
        CreateCmdResult result = callback.getResult();
        AsyncCallFuture<VolumeApiResult> future = context.future;
        VolumeInfo volume = (VolumeInfo) context.volume;

        if (result.isFailed()) {
            try {
                volume.processEvent(Event.OperationFailed);
            } catch (Exception e) {
                s_logger.debug("Failed to change state", e);
            }
            VolumeApiResult res = new VolumeApiResult(volume);
            res.setResult(result.getResult());
            future.complete(res);
            return null;
        }

        try {
            volume.processEvent(Event.OperationSuccessed);
        } catch (Exception e) {
            s_logger.debug("Failed to change state", e);
            VolumeApiResult res = new VolumeApiResult(volume);
            res.setResult(result.getResult());
            future.complete(res);
            return null;
        }

        VolumeApiResult res = new VolumeApiResult(volume);
        future.complete(res);

        return null;
    }

    @Override
    public void handleVolumeSync(DataStore store) {
        if (store == null) {
            s_logger.warn("Huh? ssHost is null");
            return;
        }
        long storeId = store.getId();

        Map<Long, TemplateProp> volumeInfos = listVolume(store);
        if (volumeInfos == null) {
            return;
        }

        List<VolumeDataStoreVO> dbVolumes = _volumeStoreDao.listByStoreId(storeId);
        List<VolumeDataStoreVO> toBeDownloaded = new ArrayList<VolumeDataStoreVO>(dbVolumes);
        for (VolumeDataStoreVO volumeStore : dbVolumes) {
            VolumeVO volume = _volumeDao.findById(volumeStore.getVolumeId());
            // Exists then don't download
            if (volumeInfos.containsKey(volume.getId())) {
                TemplateProp volInfo = volumeInfos.remove(volume.getId());
                toBeDownloaded.remove(volumeStore);
                s_logger.info("Volume Sync found " + volume.getUuid() + " already in the volume image store table");
                if (volumeStore.getDownloadState() != Status.DOWNLOADED) {
                    volumeStore.setErrorString("");
                }
                if (volInfo.isCorrupted()) {
                    volumeStore.setDownloadState(Status.DOWNLOAD_ERROR);
                    String msg = "Volume " + volume.getUuid() + " is corrupted on image store ";
                    volumeStore.setErrorString(msg);
                    s_logger.info("msg");
                    if (volumeStore.getDownloadUrl() == null) {
                        msg = "Volume (" + volume.getUuid() + ") with install path " + volInfo.getInstallPath()
                                + "is corrupted, please check in image store: " + volumeStore.getDataStoreId();
                        s_logger.warn(msg);
                    } else {
                        toBeDownloaded.add(volumeStore);
                    }

                } else { // Put them in right status
                    volumeStore.setDownloadPercent(100);
                    volumeStore.setDownloadState(Status.DOWNLOADED);
                    volumeStore.setInstallPath(volInfo.getInstallPath());
                    volumeStore.setSize(volInfo.getSize());
                    volumeStore.setPhysicalSize(volInfo.getPhysicalSize());
                    volumeStore.setLastUpdated(new Date());
                    _volumeStoreDao.update(volumeStore.getId(), volumeStore);

                    if (volume.getSize() == 0) {
                        // Set volume size in volumes table
                        volume.setSize(volInfo.getSize());
                        _volumeDao.update(volumeStore.getVolumeId(), volume);
                    }

                    if (volInfo.getSize() > 0) {
                        try {
                            _resourceLimitMgr.checkResourceLimit(_accountMgr.getAccount(volume.getAccountId()),
                                    com.cloud.configuration.Resource.ResourceType.secondary_storage, volInfo.getSize()
                                            - volInfo.getPhysicalSize());
                        } catch (ResourceAllocationException e) {
                            s_logger.warn(e.getMessage());
                            _alertMgr.sendAlert(AlertManager.ALERT_TYPE_RESOURCE_LIMIT_EXCEEDED,
                                    volume.getDataCenterId(), volume.getPodId(), e.getMessage(), e.getMessage());
                        } finally {
                            _resourceLimitMgr.recalculateResourceCount(volume.getAccountId(), volume.getDomainId(),
                                    com.cloud.configuration.Resource.ResourceType.secondary_storage.getOrdinal());
                        }
                    }
                }
                continue;
            }
            // Volume is not on secondary but we should download.
            if (volumeStore.getDownloadState() != Status.DOWNLOADED) {
                s_logger.info("Volume Sync did not find " + volume.getName() + " ready on image store " + storeId
                        + ", will request download to start/resume shortly");
                toBeDownloaded.add(volumeStore);
            }
        }

        // Download volumes which haven't been downloaded yet.
        if (toBeDownloaded.size() > 0) {
            for (VolumeDataStoreVO volumeHost : toBeDownloaded) {
                if (volumeHost.getDownloadUrl() == null) { // If url is null we
                                                           // can't initiate the
                                                           // download
                    continue;
                }
                s_logger.debug("Volume " + volumeHost.getVolumeId() + " needs to be downloaded to " + store.getName());
                // TODO: pass a callback later
                VolumeInfo vol = volFactory.getVolume(volumeHost.getVolumeId());
                createVolumeAsync(vol, store);
            }
        }

        // Delete volumes which are not present on DB.
        for (Long uniqueName : volumeInfos.keySet()) {
            TemplateProp tInfo = volumeInfos.get(uniqueName);

            //we cannot directly call expungeVolumeAsync here to
            // reuse delete logic since in this case, our db does not have
            // this template at all.
            VolumeObjectTO tmplTO = new VolumeObjectTO();
            tmplTO.setDataStore(store.getTO());
            tmplTO.setPath(tInfo.getInstallPath());
            tmplTO.setId(tInfo.getId());
            DeleteCommand dtCommand = new DeleteCommand(tmplTO);
            EndPoint ep = _epSelector.select(store);
            Answer answer = ep.sendMessage(dtCommand);
            if (answer == null || !answer.getResult()) {
                s_logger.info("Failed to deleted volume at store: " + store.getName());

            } else {
                String description = "Deleted volume " + tInfo.getTemplateName() + " on secondary storage " + storeId;
                s_logger.info(description);
            }
        }
    }

    private Map<Long, TemplateProp> listVolume(DataStore store) {
        ListVolumeCommand cmd = new ListVolumeCommand(store.getTO(), store.getUri());
        EndPoint ep = _epSelector.select(store);
        Answer answer = ep.sendMessage(cmd);
        if (answer != null && answer.getResult()) {
            ListVolumeAnswer tanswer = (ListVolumeAnswer) answer;
            return tanswer.getTemplateInfo();
        } else {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Can not list volumes for image store " + store.getId());
            }
        }

        return null;
    }

    @Override
    public SnapshotInfo takeSnapshot(VolumeInfo volume) {
        VolumeObject vol = (VolumeObject) volume;
        vol.stateTransit(Volume.Event.SnapshotRequested);

        SnapshotInfo snapshot = null;
        try {
            snapshot = snapshotMgr.takeSnapshot(volume);
        } catch (Exception e) {
            s_logger.debug("Take snapshot: " + volume.getId() + " failed: " + e.toString());
        } finally {
            if (snapshot != null) {
                vol.stateTransit(Volume.Event.OperationSucceeded);
            } else {
                vol.stateTransit(Volume.Event.OperationFailed);
            }
        }

        return snapshot;
    }

}
