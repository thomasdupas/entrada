
#!/bin/bash
DAYS_AGO=$1

#use kerberos user "hdfs"
IMPALA_OPTS=
if [ -f "$KEYTAB_FILE" ];
then
   kinit $KRB_USER -k -t $KEYTAB_FILE
   IMPALA_OPTS=-k
fi


#Target table
TARGET_TABLE="dns.domain_query_stats_2"

#get date for yesterday
day=$(date --date="$DAYS_AGO days ago" +"%-d")
year=$(date --date="$DAYS_AGO days ago" +"%Y")
month=$(date --date="$DAYS_AGO days ago" +"%-m")

impala-shell $IMPALA_OPTS -i $IMPALA_NODE -V -q  "
WITH domain_query_counts AS (
select domainname, count(1) as counts,year, month, day
from dns.queries
where year=$year and month=$month and day=$day
group by year, month, day, domainname)
,
domain_src_counts AS (
select domainname, count(distinct(src)) as counts,year, month, day
from dns.queries
where year=$year and month=$month and day=$day
group by year, month, day, domainname)
,
domain_country_counts AS (
select domainname, count(distinct(country)) as counts,year, month, day
from dns.queries
where year=$year and month=$month and day=$day
group by year, month, day, domainname)
,
domain_asn_counts AS (
select domainname, count(distinct(asn)) as counts,year, month, day
from dns.queries
where year=$year and month=$month and day=$day
group by year, month, day, domainname)
insert into dns.maartentest partition(year, month, day)
select q.domainname, q.counts, s.counts, cast(c.counts as int),asn.counts, q.year, q.month, q.day
from domain_query_counts q, domain_src_counts s, domain_country_counts c, domain_asn_counts asn
where q.domainname = s.domainname
and q.domainname = c.domainname
and q.domainname = asn.domainname;


COMPUTE INCREMENTAL STATS $TARGET_TABLE partition(year=$year, month=$month, day=$day);"


