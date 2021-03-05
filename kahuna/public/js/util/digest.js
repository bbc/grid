import angular from 'angular';

export var digest = angular.module('util.digest', []);


// Helper to $apply the fn on the scope iff we're not
// already in a $digest cycle.  Necessary because of
// the different contexts we can be called from.

// Consider briefly whether you would equally well
// be served by setting a zero timeout.

digest.value('safeApply', function (scope, fn) {
    if (scope.$$phase || scope.$root.$$phase) {
        fn();
    } else {
        scope.$apply(function () {
            fn();
        });
    }
});
