import angular from 'angular';
import template from './metadata-templates.html';

import '../util/rx';

import './metadata-templates.css';

import '../edits/service';

export const metadataTemplates = angular.module('kahuna.edits.metadataTemplates', [
  'kahuna.edits.service',
  'util.rx'
]);

metadataTemplates.controller('MetadataTemplatesCtrl', [
  '$scope',
  '$window',
  'editsService',
  function ($scope, $window, editsService) {

  let ctrl = this;

  ctrl.templateSelected = false;
  ctrl.metadataTemplates = window._clientConfig.metadataTemplates;

  function resolve(strategy, originalValue, changeToApply) {
    if (strategy === 'replace') {
      return changeToApply;
    } else if (strategy === 'append') {
      return originalValue + changeToApply;
    } else if (strategy === 'prepend') {
      return changeToApply + originalValue;
    } else {
      return originalValue;
    }
  }

  const updateUsageRight = 'events:metadata-template-apply:usage-rights';
  const updateMetadataEvent = 'events:metadata-template-apply:metadata';

  ctrl.selectTemplate = () => {
    if (ctrl.metadataTemplate) {
      updateMetadataFields();
      updateUsageRights();
    } else {
      ctrl.cancel();
    }
  };

  function updateMetadataFields() {
    if (ctrl.metadataTemplate.metadataFields && ctrl.metadataTemplate.metadataFields.length > 0) {
      ctrl.metadata = angular.copy(ctrl.originalMetadata);
      ctrl.metadataTemplate.metadataFields.forEach(field => {
        ctrl.metadata[field.name] = resolve(field.resolveStrategy, ctrl.metadata[field.name], field.value);
      });

      $scope.$emit(updateMetadataEvent, { metadata: ctrl.metadata });
    }
  }

  function updateUsageRights() {
    if (ctrl.metadataTemplate.usageRights && ctrl.metadataTemplate.usageRights.hasOwnProperty('category')) {
      $scope.$emit(updateUsageRight, { usageRights: ctrl.metadataTemplate.usageRights });
    } else {
      $scope.$emit(updateUsageRight, { usageRights: ctrl.originalUsageRights });
    }
  }

  ctrl.cancel = () => {
    ctrl.metadataTemplate = null;

    $scope.$emit(updateMetadataEvent, {metadata: ctrl.originalMetadata});
    $scope.$emit(updateUsageRight, {usageRights: ctrl.originalUsageRights});

    ctrl.saving = false;
    ctrl.onCancel();
  };

  ctrl.applyTemplate = () => {
    ctrl.saving = true;

    editsService
      .update(ctrl.image.data.userMetadata.data.metadata, ctrl.metadata, ctrl.image)
      .then(resource => ctrl.resource = resource)
      .then(() => {
        if (ctrl.metadataTemplate.usageRights) {
          editsService
            .update(ctrl.image.data.userMetadata.data.usageRights, ctrl.metadataTemplate.usageRights, ctrl.image)
            .then(() => editsService.updateMetadataFromUsageRights(ctrl.image, false));
        }
      })
      .finally(() => {
        ctrl.saving = false;
        ctrl.onSave();
      });
  };
}]);

metadataTemplates.directive('grMetadataTemplates', [function() {
  return {
    restrict: 'E',
    controller: 'MetadataTemplatesCtrl',
    controllerAs: 'ctrl',
    bindToController: true,
    template: template,
    scope: {
      image: '=',
      originalMetadata: '=metadata',
      originalUsageRights: '=usageRights',
      onCancel: '&?grOnCancel',
      onSave: '&?grOnSave'
    }
  };
}]);
