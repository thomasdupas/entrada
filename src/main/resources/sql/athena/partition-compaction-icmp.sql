CREATE TABLE ${DATABASE_NAME}.tmp_compaction
WITH (
      external_location = '${TABLE_LOC}',
      format = 'Parquet',
      parquet_compression = 'SNAPPY')
AS SELECT unixtime,icmp_type,
          icmp_code,icmp_echo_client_type,ip_ttl,
          ip_v,ip_src,
          ip_dst,ip_country,
          ip_asn,ip_len,
          l4_prot,l4_srcp,
          l4_dstp,orig_ip_ttl,
          orig_ip_v,orig_ip_src,
          orig_ip_dst,orig_l4_prot,
          orig_l4_srcp,orig_l4_dstp,
          orig_udp_sum,orig_ip_len,
          orig_icmp_type,orig_icmp_code,
          orig_icmp_echo_client_type,
          orig_dns_id,orig_dns_qname,
          orig_dns_domainname,orig_dns_len,
          orig_dns_aa,orig_dns_tc,
          orig_dns_rd,orig_dns_ra,
          orig_dns_z,orig_dns_ad,
          orig_dns_cd,orig_dns_ancount,
          orig_dns_arcount,orig_dns_nscount,
          orig_dns_qdcount,orig_dns_rcode,
          orig_dns_qtype,orig_dns_opcode,
          orig_dns_qclass,orig_dns_edns_udp,
          orig_dns_edns_version,orig_dns_edns_do,
          orig_dns_labels,svr,time_micro, server_location,cast(unixtime as timestamp),
          pcap_file,year,month,day,server
FROM ${DATABASE_NAME}.${TABLE_NAME}
WHERE year=${YEAR} AND month=${MONTH} AND day=${DAY} AND server='${SERVER}';