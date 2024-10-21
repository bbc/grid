// -using config lease definitions to create leases for image based on chosen rights category-
export function createCategoryLeases(leaseDefs, image) {
  const leaseTypes = ["allow-use", "deny-use", "allow-syndication", "deny-syndication"];
  const leases = [];
  leaseDefs.forEach((leaseDef) => {
    //-establish start date: TODAY | UPLOAD | TAKEN | TXDATE-
    const startDteType = leaseDef.startDate ?? "NONE";
    let startDate = undefined;
    switch (startDteType) {
      case ("TODAY"):
        startDate = new Date();
        break;
      case ("UPLOAD"):
        startDate = new Date(image.data.uploadTime);
        break;
      case ("TAKEN"):
        if (image.data.metadata.dateTaken) {
          startDate = new Date(image.data.metadata.dateTaken);
        }
        break;
      case ("TXDATE"):
        if (image.data.metadata.domainMetadata &&
            image.data.metadata.domainMetadata.programmes &&
            image.data.metadata.domainMetadata.programmes.originalTxDate) {
          startDate = new Date(image.data.metadata.domainMetadata.programmes.originalTxDate);
        }
        break;
      default:
        startDate = undefined;
        break;
    }
    // -check we have acceptable type and startDate-
    if (leaseTypes.includes(leaseDef.type ?? "") && startDate) {
        const lease = {};
        lease["access"] = leaseDef.type;
        lease["createdAt"] = (new Date()).toISOString();
        lease["leasedBy"] = "Usage_Rights_Category";
        lease["startDate"] = startDate.toISOString();
        lease["notes"] = leaseDef.notes ?? "";

        if (leaseDef.duration) {
          let endDate = startDate;
          endDate.setFullYear(endDate.getFullYear() + leaseDef.duration);
          lease["endDate"] = endDate.toISOString();
        }
        lease["mediaId"] = image.data.id;
        leases.push(lease);
    }
  });
  return leases;
}
