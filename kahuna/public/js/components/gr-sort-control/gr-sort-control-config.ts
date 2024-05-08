import {SortDropdownOption} from "./gr-sort-control";

export interface LabelsObject {
        [key: string]: string;
}
const defaultSortLabels: LabelsObject = {
      uploadNewOld: "Upload date (new to old)",
      oldest: "Upload date (old to new)",
      dateAddedToCollection: "Added to Collection (recent 1st)"
};
export function loadLabels(): Promise<LabelsObject> {
    return import(`../../common/resources/${window._clientConfig.staffPhotographerOrganisation}-gr-sort-control`)
      .then(module => module.default)
      .then(labels => labels)
      .catch(() => Promise.resolve(defaultSortLabels));
}

export function manageSortSelection(newSelection:string): string {
  let newVal;
  switch (newSelection) {
    case "uploadNewOld":
      newVal = undefined;
      break;
    case "oldest":
      newVal = "oldest";
      break;
    case "dateAddedToCollection":
      newVal = "dateAddedToCollection";
      break;
    default:
      newVal = undefined;
      break;
  }
  return newVal;
}

export const SortOptions: SortDropdownOption[] = [
   {
     value: "uploadNewOld",
     label: defaultSortLabels.uploadNewOld,
     isCollection: false
   },
   {
     value: "oldest",
     label: defaultSortLabels.oldest,
     isCollection: false
   },
   {
     value: "dateAddedToCollection",
     label: defaultSortLabels.dateAddedToCollection,
     isCollection: true
   }
];

export const DefaultSortOption: SortDropdownOption = SortOptions[0];
export const CollectionSortOption: SortDropdownOption = SortOptions[2];
