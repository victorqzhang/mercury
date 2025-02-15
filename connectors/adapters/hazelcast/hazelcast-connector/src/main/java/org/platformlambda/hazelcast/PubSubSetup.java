/*

    Copyright 2018-2022 Accenture Technology

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */

package org.platformlambda.hazelcast;

import org.platformlambda.core.annotations.CloudService;
import org.platformlambda.core.models.CloudSetup;
import org.platformlambda.core.system.PubSub;
import org.platformlambda.hazelcast.services.PubSubManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This cloud service provides pub/sub service.
 * This allows the user application to run as a standalone executable and
 * communicate with each other purely using pub/sub.
 *
 * For real-time and pub/sub use cases, please use the "hazelcast" connector.
 * It offers pub/sub service and also uses Hazelcast as a service mesh
 * to support topic virtualization and closed user groups.
 */
@CloudService(name="hazelcast.pubsub")
public class PubSubSetup implements CloudSetup {
    private static final Logger log = LoggerFactory.getLogger(PubSubSetup.class);

    @Override
    public void initialize() {
        if (!PubSub.getInstance().featureEnabled()) {
            PubSub.getInstance().enableFeature(new PubSubManager());
            log.info("Started");
        }
    }
    
}
