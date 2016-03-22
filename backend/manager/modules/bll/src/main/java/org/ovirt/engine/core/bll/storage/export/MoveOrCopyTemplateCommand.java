package org.ovirt.engine.core.bll.storage.export;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ovirt.engine.core.bll.VmHandler;
import org.ovirt.engine.core.bll.VmTemplateHandler;
import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.storage.disk.image.ImagesHandler;
import org.ovirt.engine.core.bll.storage.domain.StorageDomainCommandBase;
import org.ovirt.engine.core.bll.utils.PermissionSubject;
import org.ovirt.engine.core.bll.utils.VmDeviceUtils;
import org.ovirt.engine.core.bll.validator.storage.MultipleStorageDomainsValidator;
import org.ovirt.engine.core.bll.validator.storage.StorageDomainValidator;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.MoveOrCopyImageGroupParameters;
import org.ovirt.engine.core.common.action.MoveOrCopyParameters;
import org.ovirt.engine.core.common.action.VdcActionParametersBase;
import org.ovirt.engine.core.common.action.VdcActionType;
import org.ovirt.engine.core.common.action.VdcReturnValueBase;
import org.ovirt.engine.core.common.asynctasks.EntityInfo;
import org.ovirt.engine.core.common.businessentities.IVdcQueryable;
import org.ovirt.engine.core.common.businessentities.OvfEntityData;
import org.ovirt.engine.core.common.businessentities.StorageDomain;
import org.ovirt.engine.core.common.businessentities.storage.DiskImage;
import org.ovirt.engine.core.common.businessentities.storage.ImageOperation;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.vdscommands.GetImagesListVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.common.vdscommands.VDSReturnValue;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.utils.transaction.TransactionMethod;
import org.ovirt.engine.core.utils.transaction.TransactionSupport;

@Deprecated
public abstract class MoveOrCopyTemplateCommand<T extends MoveOrCopyParameters> extends StorageDomainCommandBase<T> {

    /**
     * Map which contains the disk id (new generated id if the disk is cloned) and the disk parameters from the export
     * domain.
     */
    protected final Map<Guid, DiskImage> newDiskIdForDisk = new HashMap<>();
    protected Map<Guid, Guid> imageToDestinationDomainMap;
    protected Map<Guid, DiskImage> imageFromSourceDomainMap;
    private List<PermissionSubject> permissionCheckSubject;
    private List<DiskImage> templateDisks;
    private StorageDomain sourceDomain;
    private Guid sourceDomainId = Guid.Empty;

    /**
     * Constructor for command creation when compensation is applied on startup
     */
    public MoveOrCopyTemplateCommand(Guid commandId) {
        super(commandId);
    }

    public MoveOrCopyTemplateCommand(T parameters, CommandContext commandContext) {
        super(parameters, commandContext);
    }

    @Override
    protected void init(T parameters) {
        super.init(parameters);

        setVmTemplateId(parameters.getContainerId());
        parameters.setEntityInfo(new EntityInfo(VdcObjectType.VmTemplate, getVmTemplateId()));
        imageToDestinationDomainMap = getParameters().getImageToDestinationDomainMap();
        imageFromSourceDomainMap = new HashMap<>();
    }

    protected StorageDomain getSourceDomain() {
        if (sourceDomain == null && !Guid.Empty.equals(sourceDomainId)) {
            sourceDomain = getStorageDomainDao().getForStoragePool(sourceDomainId, getStoragePool().getId());
        }
        return sourceDomain;
    }

    protected void setSourceDomainId(Guid storageId) {
        sourceDomainId = storageId;
    }

    protected List<DiskImage> getTemplateDisks() {
        if (templateDisks == null && getVmTemplate() != null) {
            VmTemplateHandler.updateDisksFromDb(getVmTemplate());
            templateDisks = getVmTemplate().getDiskList();
        }
        return templateDisks;
    }

    protected boolean validateUnregisteredEntity(IVdcQueryable entityFromConfiguration, OvfEntityData ovfEntityData) {
        if (ovfEntityData == null && !getParameters().isImportAsNewEntity()) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_UNSUPPORTED_OVF);
        }
        if (entityFromConfiguration == null) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_OVF_CONFIGURATION_NOT_SUPPORTED);
        }

        for (DiskImage image : getImages()) {
            StorageDomain sd = getStorageDomainDao().getForStoragePool(
                    image.getStorageIds().get(0), getStoragePool().getId());
            if (!validate(new StorageDomainValidator(sd).isDomainExistAndActive())) {
                return false;
            }
        }
        if (!getStorageDomain().getStorageDomainType().isDataDomain()) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_STORAGE_DOMAIN_TYPE_UNSUPPORTED,
                    String.format("$domainId %1$s", getParameters().getStorageDomainId()),
                    String.format("$domainType %1$s", getStorageDomain().getStorageDomainType()));
        }
        return true;
    }

    protected List<DiskImage> getImages() {
        return Collections.<DiskImage>emptyList();
    }

    protected boolean isImagesAlreadyOnTarget() {
        return getParameters().isImagesExistOnTargetStorageDomain();
    }

    protected void moveOrCopyAllImageGroups() {
        moveOrCopyAllImageGroups(getVmTemplateId(), getTemplateDisks());
    }

    protected void moveOrCopyAllImageGroups(final Guid containerID, final Iterable<DiskImage> disks) {
        TransactionSupport.executeInNewTransaction(new TransactionMethod<Void>() {

            @Override
            public Void runInTransaction() {
                for (DiskImage disk : disks) {
                    VdcReturnValueBase vdcRetValue = runInternalActionWithTasksContext(
                            getImagesActionType(),
                            buildModeOrCopyImageGroupParameters(containerID, disk));

                    getReturnValue().getVdsmTaskIdList().addAll(vdcRetValue.getInternalVdsmTaskIdList());
                }
                return null;
            }

            private MoveOrCopyImageGroupParameters buildModeOrCopyImageGroupParameters(
                    final Guid containerID, DiskImage disk) {
                MoveOrCopyImageGroupParameters params = new MoveOrCopyImageGroupParameters(
                        containerID, disk.getId(), disk.getImageId(),
                        getParameters().getStorageDomainId(), ImageOperation.Copy);
                params.setParentCommand(getActionType());
                params.setEntityInfo(getParameters().getEntityInfo());
                params.setAddImageDomainMapping(true);
                params.setSourceDomainId(imageFromSourceDomainMap.get(disk.getId()).getStorageIds().get(0));
                params.setParentParameters(getParameters());
                return params;
            }
        });
    }

    protected boolean checkIfDisksExist(Iterable<DiskImage> disksList) {
        Map<Guid, List<Guid>> alreadyRetrieved = new HashMap<>();
        for (DiskImage disk : disksList) {
            Guid targetStorageDomainId = imageToDestinationDomainMap.get(disk.getId());
            List<Guid> imagesOnStorageDomain = alreadyRetrieved.get(targetStorageDomainId);

            if (imagesOnStorageDomain == null) {
                VDSReturnValue returnValue = runVdsCommand(
                        VDSCommandType.GetImagesList,
                        new GetImagesListVDSCommandParameters(targetStorageDomainId, getStoragePoolId())
                );

                if (returnValue.getSucceeded()) {
                    imagesOnStorageDomain = (List<Guid>) returnValue.getReturnValue();
                    alreadyRetrieved.put(targetStorageDomainId, imagesOnStorageDomain);
                } else {
                    return failValidation(EngineMessage.ERROR_GET_IMAGE_LIST,
                            String.format("$sdName %1$s", getStorageDomain(targetStorageDomainId).getName()));
                }
            }

            if (imagesOnStorageDomain.contains(disk.getId())) {
                return failValidation(EngineMessage.ACTION_TYPE_FAILED_STORAGE_DOMAIN_ALREADY_CONTAINS_DISK);
            }
        }
        return true;
    }

    protected void endMoveOrCopyCommand() {
        endActionOnAllImageGroups();
        endVmTemplateRelatedOps();
        setSucceeded(true);
    }

    protected final void endVmTemplateRelatedOps() {
        if (getVmTemplate() != null) {
            VmDeviceUtils.setVmDevices(getVmTemplate());
            VmHandler.updateVmInitFromDB(getVmTemplate(), true);
            incrementDbGeneration();
            VmTemplateHandler.unlockVmTemplate(getVmTemplateId());
        }
        else {
            setCommandShouldBeLogged(false);
            log.warn("MoveOrCopyTemplateCommand::EndMoveOrCopyCommand: VmTemplate is null, not performing full endAction");
        }
    }

    protected void incrementDbGeneration() {
        getVmStaticDao().incrementDbGeneration(getVmTemplate().getId());
    }

    @Override
    protected void endSuccessfully() {
        endMoveOrCopyCommand();
    }

    @Override
    protected void endWithFailure() {
        endMoveOrCopyCommand();
    }

    protected void endActionOnAllImageGroups() {
        for (VdcActionParametersBase p : getParameters().getImagesParameters()) {
            getBackend().endAction(getImagesActionType(),
                    p,
                    getContext().clone().withoutCompensationContext().withoutExecutionContext().withoutLock());
        }
    }

    protected VdcActionType getImagesActionType() {
        return VdcActionType.CopyImageGroup;
    }

    protected StorageDomain getStorageDomain(Guid domainId) {
        return getStorageDomainDao().getForStoragePool(domainId, getStoragePool().getId());
    }

    /**
     * Space Validations are done using data extracted from the disks. The disks in question in this command
     * don't have all the needed data, and in order not to contaminate the command's data structures, an alter
     * one is created specifically fo this validation - hence dummy.
     */
    protected List<DiskImage> createDiskDummiesForSpaceValidations(List<DiskImage> disksList) {
        List<DiskImage> dummies = new ArrayList<>(disksList.size());
        for (DiskImage image : disksList) {
            Guid targetSdId = imageToDestinationDomainMap.get(image.getId());
            DiskImage dummy = ImagesHandler.createDiskImageWithExcessData(image, targetSdId);
            dummies.add(dummy);
        }
        return dummies;
    }

    protected boolean validateSpaceRequirements(Collection<DiskImage> diskImages) {
        MultipleStorageDomainsValidator sdValidator = createMultipleStorageDomainsValidator(diskImages);
        if (!validate(sdValidator.allDomainsExistAndActive())
                || !validate(sdValidator.allDomainsWithinThresholds())) {
            return false;
        }

        if (getParameters().getCopyCollapse()) {
            return validate(sdValidator.allDomainsHaveSpaceForClonedDisks(diskImages));
        }

        return validate(sdValidator.allDomainsHaveSpaceForDisksWithSnapshots(diskImages));
    }

    protected MultipleStorageDomainsValidator createMultipleStorageDomainsValidator(Collection<DiskImage> diskImages) {
        return new MultipleStorageDomainsValidator(getStoragePoolId(),
                ImagesHandler.getAllStorageIdsForImageIds(diskImages));
    }

    protected void ensureDomainMap(Collection<DiskImage> images, Guid defaultDomainId) {
        if (imageToDestinationDomainMap == null) {
            imageToDestinationDomainMap = new HashMap<>();
        }
        if (imageToDestinationDomainMap.isEmpty() && images != null && defaultDomainId != null) {
            for (DiskImage image : images) {
                if (isImagesAlreadyOnTarget()) {
                    imageToDestinationDomainMap.put(image.getId(), image.getStorageIds().get(0));
                } else if (!Guid.Empty.equals(defaultDomainId)) {
                    imageToDestinationDomainMap.put(image.getId(), defaultDomainId);
                }
            }
        }
    }

    @Override
    public List<PermissionSubject> getPermissionCheckSubjects() {
        if (permissionCheckSubject == null) {
            if (imageToDestinationDomainMap == null || imageToDestinationDomainMap.isEmpty()) {
                permissionCheckSubject = super.getPermissionCheckSubjects();
            } else {
                permissionCheckSubject = new ArrayList<>();
                Set<PermissionSubject> permissionSet = new HashSet<>();
                for (Guid storageId : imageToDestinationDomainMap.values()) {
                    permissionSet.add(new PermissionSubject(storageId,
                            VdcObjectType.Storage,
                            getActionType().getActionGroup()));
                }
                permissionCheckSubject.addAll(permissionSet);
            }

        }
        return permissionCheckSubject;
    }
}
