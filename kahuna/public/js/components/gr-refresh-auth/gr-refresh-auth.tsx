import * as React from "react";
import * as angular from "angular";
import { react2angular } from "react2angular";
import refresh from '@bbc/partner-platform-ui-refresh-library';
import {mediaApi}   from '../../services/api/media-api';


const success = () => {
	console.log('Successful Refresh!');
};

const error = (message: string) => {
	console.log('Error on Refresh!', message);
};

// mediaApi.getSession().then(session => {
//   console.log('user session', session);
// });
console.log("mediaApi", mediaApi);
// fetch('https://media-auth.local.dev-gutools.co.uk/session', {
//   method: 'GET',
//   credentials: 'include',
//   headers: {
//     'Content-Type': 'application/json',
//   },
// }).then(response => {
//   console.log('response', response);
// });

// const res = fetch("https://media-auth.local.dev-gutools.co.uk/session", {
//     "credentials": "omit",
//     "headers": {
//         "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:109.0) Gecko/20100101 Firefox/110.0",
//         "Accept": "*/*",
//         "Accept-Language": "en-US,en;q=0.5",
//         "Sec-Fetch-Dest": "empty",
//         "Sec-Fetch-Mode": "cors",
//         "Sec-Fetch-Site": "same-site",
//         "Pragma": "no-cache",
//         "Cache-Control": "no-cache"
//     },
//     "referrer": "https://media.local.dev-gutools.co.uk/",
//     "method": "GET",
//     "mode": "cors"
// });

const res1 = fetch("https://auth.images.int.tools.bbc.co.uk/session", {
    "credentials": "include",
    "headers": {
        "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:109.0) Gecko/20100101 Firefox/110.0",
        "Accept": "application/vnd.argo+json",
        "Accept-Language": "en-US,en;q=0.5",
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "same-site",
        "Pragma": "no-cache",
        "Cache-Control": "no-cache"
    },
    "referrer": "https://images.int.tools.bbc.co.uk/",
    "method": "GET",
    "mode": "cors"
});

const res2 = fetch("https://auth.images.int.tools.bbc.co.uk/_ppap/session", {
    "credentials": "omit",
    "headers": {
        "User-Agent": "PartnerPlatformRefresh/1.0.0",
        "Accept": "*/*",
        "Accept-Language": "en-US,en;q=0.5",
        "X-Requested-With": "XMLHttpRequest",
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "same-site"
    },
    "referrer": "https://images.int.tools.bbc.co.uk/",
    "method": "GET",
    "mode": "cors"
});

res1.then(response => {
  console.log('response', response);
  response.json().then(json => {
    console.log('json', json);
  } );
});

res2.then(response => {
  console.log('response2', response);
  response.json().then(json => {
    console.log('json2', json);
  } );
});


const options = {
	success,
	error,
	retryWindowPeriod: 10,
	retryAttempts: 3,
  basePathOverride: window._clientConfig.accessProxyBasePath
};



const GrRefreshAuth = () => {
  console.log('GrRefreshAuth');
  refresh(options);
  return (
    <div id="pp-refresh">
    </div>
  );
};


export const grRefreshAuth = angular.module('gr.refreshAuth', [])
  .component('grRefreshAuth', react2angular(GrRefreshAuth));
