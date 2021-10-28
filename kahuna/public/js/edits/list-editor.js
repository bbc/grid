import angular from 'angular';
import template from './list-editor.html';
import templateCompact from './list-editor-compact.html';
import './list-editor.css';

import '../search/query-filter';
import '../services/label';

export var listEditor = angular.module('kahuna.edits.listEditor', [
    'kahuna.search.filters.query',
    'kahuna.services.label'
]);

listEditor.controller('ListEditorCtrl', [
    '$rootScope',
    '$scope',
    '$window',
    '$timeout',
    'labelService',
   function($rootScope,
            $scope,
            $window,
            $timeout,
            labelService) {

    var ctrl = this;

    $scope.$watch(() => ctrl.image.data.userMetadata.data.labels, newLabels => {
        ctrl.labels = newLabels;
    });

     function saveFailed(e) {
       console.error(e);
        $window.alert('Something went wrong when saving, please try again!');
    }

    ctrl.addLabels = labels => {
        ctrl.adding = true;

        labelService.add(ctrl.image, labels)
            .then(img => {
                ctrl.image = img;
                ctrl.labels = ctrl.image.data.userMetadata.data.labels;
            })
            .catch(saveFailed)
            .finally(() => {
                ctrl.adding = false;
            });
    };

    ctrl.labelsBeingRemoved = new Set();
    ctrl.removeLabel = label => {
        ctrl.labelsBeingRemoved.add(label);

        labelService.remove(ctrl.image, label)
            .then(img => {
                ctrl.image = img;
                ctrl.labels = ctrl.image.data.userMetadata.data.labels;
            })
            .catch(saveFailed)
            .finally(() => {
                ctrl.labelsBeingRemoved.delete(label);
            });
    };

    ctrl.removeLabels = () => {
        ctrl.labels.data.map(label => ctrl.removeLabel(label.data));
    };

    const batchAddLabelsEvent = 'events:batch-apply:add-labels';
    const batchRemoveLabelsEvent = 'events:batch-apply:remove-labels';

    if (Boolean(ctrl.withBatch)) {
        $scope.$on(batchAddLabelsEvent, (e, labels) => ctrl.addLabels(labels));
        $scope.$on(batchRemoveLabelsEvent, () => ctrl.removeLabels());

        ctrl.batchApplyLabels = () => {
            var labels = ctrl.labels.data.map(label => label.data);

            if (labels.length > 0) {
                $rootScope.$broadcast(batchAddLabelsEvent, labels);
            } else {
                ctrl.confirmDelete = true;

                $timeout(() => {
                    ctrl.confirmDelete = false;
                }, 5000);
            }
        };

        ctrl.batchRemoveLabels = () => {
            ctrl.confirmDelete = false;
            $rootScope.$broadcast(batchRemoveLabelsEvent);
        };
    }

}]);

listEditor.directive('uiListEditor', [function() {
    return {
        restrict: 'E',
        scope: {
            // Annoying that we can't make a uni-directional binding
            // as we don't really want to modify the original
            image: '=',
            withBatch: '=?'
        },
        controller: 'ListEditorCtrl',
        controllerAs: 'ctrl',
        bindToController: true,
        template: template
    };
}]);

listEditor.directive('uiListEditorCompact', [function() {
    return {
        restrict: 'E',
        scope: {
            // Annoying that we can't make a uni-directional binding
            // as we don't really want to modify the original
            image: '=',
            disabled: '='
        },
        controller: 'ListEditorCtrl',
        controllerAs: 'ctrl',
        bindToController: true,
        template: templateCompact
    };
}]);
