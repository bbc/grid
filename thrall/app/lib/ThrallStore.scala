package lib

import com.gu.mediaservice.lib

class ThrallStore(config: ThrallConfig) extends lib.ImageIngestOperations(config.quarantineBucket, config.imageBucket, config.thumbnailBucket, config, config.isVersionedS3)
