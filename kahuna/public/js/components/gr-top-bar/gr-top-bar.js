import angular from 'angular';
import './gr-top-bar.css';

export var topBar = angular.module('gr.topBar', []);

topBar.directive('grTopBar', [function() {
    return {
        restrict: 'E',
        transclude: 'replace',
        scope: {
            fixed: '='
        },
        template: `<ng:transclude class="gr-top-bar-inner"
                                  ng:class="{'gr-top-bar-inner--fixed': fixed}">
                   </ng:transclude>`
    };
}]);

topBar.directive('grTopBarNav', [function() {
    return {
        restrict: 'E',
        transclude: true,
        // Annoying to have to hardcode root route here, but only
        // way I found to clear $stateParams from uiRouter...
        template: `
        <div class="home-link-container">
            <a href="/search" class="home-link__logo" title="Home"></a>
            <div class="home-link__border"></div>
            <a href="/search" class="home-link__images" title="Home">
                Images
                <div class="home-link__images__environment">BETA</div>
            </a>
            <div class="home-link__border"></div>
        </div>
        <ng:transclude></ng:transclude>`
    };
}]);

topBar.directive('grTopBarActions', [function() {
    return {
        restrict: 'E',
        transclude: true,
        // Always have user actions at the end of actions
        template: `<ng:transclude></ng:transclude>
                   <ui-user-actions></ui-user-actions>`
    };
}]);
