function readLeases(image) {
  return image.data.leases.data;
}

export function getApiImageAndApiLeasesIfUpdated(image, apiImage) {
    const apiLeases = readLeases(apiImage);
    const leases = readLeases(image);
    const apiImageAndApiLeases = {image: apiImage, leases: apiLeases};
    const isNewlyCreated = leases.lastModified === null;
    if (isNewlyCreated) {
      const apiImageLeasesAreCreated = apiLeases.lastModified !== null;
      if (apiImageLeasesAreCreated) {
        return apiImageAndApiLeases;
      } else {
        return undefined;
      }
    } else {
      const apiImageLeasesAreUpdated = function() {
        const currentLastModified = new Date(apiLeases.lastModified);
        const previousLastModified = new Date(leases.lastModified);
        return currentLastModified > previousLastModified;
      };
      if (apiImageLeasesAreUpdated()) {
        return apiImageAndApiLeases;
      } else {
        return undefined;
      }
    }
  };


