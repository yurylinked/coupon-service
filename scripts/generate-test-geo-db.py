# Regenerates src/test/resources/geo/test-country.mmdb: pip install mmdb-writer netaddr, then
# python3 scripts/generate-test-geo-db.py src/test/resources/geo/test-country.mmdb

import sys

from mmdb_writer import MMDBWriter
from netaddr import IPSet

writer = MMDBWriter(ip_version=6, ipv4_compatible=True, database_type="DBIP-Country-Lite",
                    description={"en": "Test fixture built from RFC 5737 and RFC 3849 documentation ranges"})
writer.insert_network(IPSet(["203.0.113.0/24", "2001:db8::/32"]), {"country": {"iso_code": "PL"}})
writer.insert_network(IPSet(["198.51.100.0/24"]), {"country": {"iso_code": "DE"}})
writer.insert_network(IPSet(["192.0.2.0/24"]), {"continent": {"code": "EU"}})
writer.insert_network(IPSet(["233.252.0.0/24"]), {"country": {"iso_code": "XK"}})
writer.to_db_file(sys.argv[1])
