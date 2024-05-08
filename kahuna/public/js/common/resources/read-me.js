/* ******************************************************************************************
Resources directory should contain organisation control specific labels
Expected name format is <<org name>>-<<control name>>.json
e.g ABC-gr-sort-control.json - this will provide ABC specific labels for the sort control
this will allow UI flexibility between ORG implementations
File content expected to be;
{
  "<<key>>": ""<<value>>"",
  ...
  }
consistent with the control requirements
The control should implement defaults on the assumption the org specific file does not exist
The org specific content can be deployed as part of application build process
******************************************************************************************** */
