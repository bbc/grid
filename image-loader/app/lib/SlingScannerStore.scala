package lib.storage

import lib.ImageLoaderConfig
import com.gu.mediaservice.lib

class SlingScannerStore(config: ImageLoaderConfig) extends lib.ImageIngestOperations(config.quarantineBucket, config.thumbnailBucket, config)
