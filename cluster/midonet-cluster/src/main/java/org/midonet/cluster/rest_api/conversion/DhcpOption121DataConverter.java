/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.rest_api.conversion;

import org.midonet.cluster.data.dhcp.Opt121;
import org.midonet.cluster.rest_api.models.DhcpOption121;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;

public class DhcpOption121DataConverter {

    public static DhcpOption121 fromData(Opt121 opt121) {
        DhcpOption121 opt = new DhcpOption121();
        opt.destinationLength = opt121.getRtDstSubnet().getPrefixLen();
        opt.destinationPrefix = opt121.getRtDstSubnet().toUnicastString();
        opt.gatewayAddr = opt121.getGateway().toString();
        return opt;
    }

    public static Opt121 toData(DhcpOption121 dto) {
        Opt121 data = new Opt121();
        data.setGateway(IPv4Addr.fromString(dto.gatewayAddr));
        data.setRtDstSubnet(new IPv4Subnet(dto.destinationPrefix,
                                           dto.destinationLength));
        return data;
    }

}