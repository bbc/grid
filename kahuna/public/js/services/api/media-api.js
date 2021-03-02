import angular from 'angular';
// TODO: make theseus-angular it export its module
import '../../util/theseus-angular';

export var mediaApi = angular.module('kahuna.services.api.media', [
    'theseus'
]);

mediaApi.factory('mediaApi',
                 ['mediaApiUri', 'theseus.client',
                  function(mediaApiUri, client) {

    var root = client.resource(mediaApiUri);
    var session;
    function searchConfig(nameKey, myArray){
        for (var i=0; i < myArray.length; i++) {
            if (myArray[i].name === nameKey) {
                return myArray[i];
            }
        }
    }

    function reconstructQuery(array){
      let stringQuery = "";
      for(var i = 0; i < array.length; i++){
        	if(i % 2 != 0) stringQuery += array[i]+" ";
        	else stringQuery += JSON.stringify(array[i])+":";
      }

      return stringQuery;
    }

    function search(query = '', {ids, since, until, archived, valid, free,
                                 payType, uploadedBy, offset, length, orderBy,
                                 takenSince, takenUntil,
                                 modifiedSince, modifiedUntil, hasRightsAcquired, hasCrops,
                                 syndicationStatus} = {}) {

        const splitQuery  = query.split(/([a-zA-Z]+):/);
        splitQuery.shift();
        if(splitQuery.length % 2 == 0){
        for(var i = 0; i < splitQuery.length - 1; i++){
                const field = splitQuery[i].trim();
                const value = splitQuery[i+1].trim();
                const getSearchableMetadata = window._clientConfig.fileMetadataConfig.
                                              filter(res => res.displaySearchHint == true).
                                              filter(f => f.alias == field);
                if(getSearchableMetadata.length == 1)
                splitQuery[i] = getSearchableMetadata[0].elasticsearchPath
          }
        }

        return root.follow('search', {
            q:          reconstructQuery(splitQuery),
            since:      since,
            free:       free,
            payType:    payType,
            until:      until,
            takenSince: takenSince,
            takenUntil: takenUntil,
            modifiedSince: modifiedSince,
            modifiedUntil: modifiedUntil,
            ids:        ids,
            uploadedBy: uploadedBy,
            valid:      valid,
            archived:   archived,
            offset:     offset,
            length:     angular.isDefined(length) ? length : 50,
            orderBy:    getOrder(orderBy),
            hasRightsAcquired: maybeStringToBoolean(hasRightsAcquired),
            hasExports: maybeStringToBoolean(hasCrops), // Grid API calls crops exports...
            syndicationStatus: syndicationStatus
        }).get();
    }

    function maybeStringToBoolean(maybeString) {
        if (maybeString === 'true') {
            return true;
        }
        if (maybeString === 'false') {
            return false;
        }

        return undefined;
    }

    function getOrder(orderBy) {
        if (orderBy === 'dateAddedToCollection') {
            return 'dateAddedToCollection';
        }
        else {
            return orderBy === 'oldest' ? 'uploadTime' : '-uploadTime';
        }
    }

    function find(id) {
        // FIXME: or use lazy resource?
        return root.follow('image', {id: id}).get();
    }

    function fileMetadata(image, {include} = {}) {
      return image.follow('fileMetadata', { include: include }).get();
    }

    function getSession() {
        // TODO: workout how we might be able to memoize this function but still
        // play nice with changes that might occur in the API (cache-header?).
        return session || (session = root.follow('session').getData());
    }

    function metadataSearch(field, { q }) {
        return root.follow('metadata-search', { field, q }).get();
    }

    function labelSearch({ q }) {
        return root.follow('label-search', { q }).get();
    }

    function labelsSuggest({ q }) {
        return root.follow('suggested-labels', { q }).get();
    }

    function delete_(image) {
        return image.perform('delete');
    }

    return {
        root,
        search,
        find,
        fileMetadata,
        getSession,
        metadataSearch,
        labelSearch,
        labelsSuggest,
        delete: delete_
    };
}]);
