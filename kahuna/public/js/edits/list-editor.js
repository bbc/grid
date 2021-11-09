import angular from 'angular';
import template from './list-editor.html';
import templateCompact from './list-editor-compact.html';
import {List} from 'immutable';
import './list-editor.css';

import '../search/query-filter';

export var listEditor = angular.module('kahuna.edits.listEditor', [
    'kahuna.search.filters.query',
    'kahuna.services.image-logic',
]);

listEditor.controller('ListEditorCtrl', [
    '$rootScope',
    '$scope',
    '$window',
    '$timeout',
    'imageLogic',
    function($rootScope,
            $scope,
            $window,
            $timeout,
            imageLogic) {
    var ctrl = this;

    const retrieveElements = (images) => List(images).flatMap(img => ctrl.accessor(img)).toArray();

    $scope.$watchCollection('ctrl.images', updatedImages => {
        debugger;
        updateHandler(updatedImages);
    }, true);

    const updateHandler = (updatedImages) => {
        ctrl.images = ctrl.images.map(img => updatedImages.find(x => imageLogic.isSameImage(x, img)) || img);
        ctrl.list = retrieveElements(ctrl.images);
    };

    const updateListener = $rootScope.$on('images-updated', (e, updatedImages) => {
        updateHandler(updatedImages);
    });

    ctrl.list = retrieveElements(ctrl.images);

    function saveFailed(e) {
        console.error(e);
        $window.alert('Something went wrong when saving, please try again!');
    }

    ctrl.addElements = elements => {
        ctrl.adding = true;

        ctrl.addToImages(ctrl.images, elements)
            .then(imgs => {
                ctrl.images = imgs;
                ctrl.list = retrieveElements(ctrl.images);
            })
            .catch(saveFailed)
            .finally(() => {
                ctrl.adding = false;
            });
    };

    ctrl.elementsBeingRemoved = new Set();
    ctrl.removeElement = element => {
        ctrl.elementsBeingRemoved.add(element);

        ctrl.removeFromImages(ctrl.images, element)
            .then(imgs => {
                ctrl.images = imgs;
                ctrl.list = retrieveElements(ctrl.images);
            })
            .catch(saveFailed)
            .finally(() => {
                ctrl.elementsBeingRemoved.delete(element);
            });
    };

    ctrl.removeAll = () => {
        ctrl.list.forEach(element => ctrl.removeFromImages(ctrl.images, element));
    };

    const batchAddEvent = 'events:batch-apply:add-all';
    const batchRemoveEvent = 'events:batch-apply:remove-all';

    if (Boolean(ctrl.withBatch)) {
        $scope.$on(batchAddEvent, (e, elements) => ctrl.addElements(elements));
        $scope.$on(batchRemoveEvent, () => ctrl.removeAll());

        ctrl.batchApply = () => {
            var elements = ctrl.list;

            if (elements.length > 0) {
                $rootScope.$broadcast(batchAddEvent, elements);
            } else {
                ctrl.confirmDelete = true;

                $timeout(() => {
                    ctrl.confirmDelete = false;
                }, 5000);
            }
        };

        ctrl.batchRemove = () => {
            ctrl.confirmDelete = false;
            $rootScope.$broadcast(batchRemoveEvent);
        };
    }

    $scope.$on('$destroy', function() {
        updateListener();
    });
}]);

listEditor.directive('uiListEditor', [function() {
    return {
        restrict: 'E',
        scope: {
            // Annoying that we can't make a uni-directional binding
            // as we don't really want to modify the original
            images: '=',
            withBatch: '=?',
            addToImages: '=',
            removeFromImages: '=',
            accessor: '='
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
            images: '=',
            disabled: '=',
            addToImages: '=',
            removeFromImages: '=',
            accessor: '='
        },
        controller: 'ListEditorCtrl',
        controllerAs: 'ctrl',
        bindToController: true,
        template: templateCompact
    };
}]);
