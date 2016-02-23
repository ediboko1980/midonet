/*
 * Copyright 2016 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.api.auth.keystone.v2;

import org.codehaus.jackson.annotate.JsonProperty;

public class Endpoint {

    @JsonProperty("id")
    public String id;
    @JsonProperty("adminURL")
    public String adminUrl;
    @JsonProperty("internalURL")
    public String internalUrl;
    @JsonProperty("publicURL")
    public String publicUrl;
    @JsonProperty("region")
    public String region;

    public Endpoint() { }

    public Endpoint(String id,
                    String adminUrl,
                    String internalUrl,
                    String publicUrl,
                    String region) {
        this.id = id;
        this.adminUrl = adminUrl;
        this.internalUrl = internalUrl;
        this.publicUrl = publicUrl;
        this.region = region;
    }

}
