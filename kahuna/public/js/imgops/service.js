import angular from 'angular';

export var imgops = angular.module('kahuna.imgops', []);

imgops.factory('imgops', ['$window', function($window) {
    const quality = 85;

    const lowResMaxWidth  = 800;
    const lowResMaxHeight = 800;
    const isFF = !!$window.navigator.userAgent.match(/firefox/i);

    function getFullScreenUri(image) {
        let { width: w, height: h } = $window.screen;
        if (isFF) {
            const zoom = $window.devicePixelRatio;
            if (zoom !== 1) {
                h = Math.round(h * zoom);
                w = Math.round(w * zoom);
            }
        }

        return getOptimisedUri(image, { w, h, q: quality });
    }

    function getLowResUri(image) {
        return getOptimisedUri(image, {
            w: lowResMaxWidth,
            h: lowResMaxHeight,
            q: quality
        });
    }

    function getLowResDownloadUri(image) {
        return getOptimisedDownloadUri(image, {
            width: lowResMaxWidth,
            height: lowResMaxHeight,
            quality: quality
        });
    }

    function getOptimisedUri(image, options) {
        // Normally the `optimised`/`optimisedPng` links are URI Templates that can be expanded client-side
        // (with concrete w/h/q values) into a URL that's directly usable as an `<img src>` - either imgops's
        // or (when unsigned) imgproxy's own URL, so no round-trip to media-api is needed to view an image.
        //
        // When imgproxy is in use *and* configured to sign requests, those links instead point at a
        // media-api endpoint that must be resolved (an authenticated GET, returning the actual imgproxy URL
        // as JSON) before we have a URL that can be used directly - see `ImageResponse.makeImgopsUri` and
        // `MediaApi.resolvedImageUrl` (server-side) for why. Either way, the end result used for `<img src>`
        // is always a direct link to the image-resizing service (imgops/imgproxy), never media-api itself.
        const rel = image.data.optimisedPng ? 'optimisedPng' : 'optimised';
        const link = image.follow(rel, options);

        const uri = image.data.optimisedUrlsRequireResolution ?
            link.get().then(resolved => resolved.data.url) :
            link.getUri();

        return uri.catch(() => {
            const asset = image.data.optimisedPng ? image.optimisedPng : image.source;
            return asset.secureUrl || asset.file;
        });
    }

    function getOptimisedDownloadUri(image, options) {
        return image.follow('downloadOptimised', options).getUri().catch(() => {
            return image.follow('download');
        });
    }

    return {
        getFullScreenUri,
        getLowResUri,
        getLowResDownloadUri
    };

}]);
