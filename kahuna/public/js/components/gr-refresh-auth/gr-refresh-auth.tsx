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

mediaApi.getSession().then(session => {
  console.log('user session', session);
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
