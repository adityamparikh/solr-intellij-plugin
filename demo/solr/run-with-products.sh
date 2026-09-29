#!/bin/bash
# Starts the demo's Solr, creating the `products` collection from this repository's configset the
# first time. compose.yaml runs this as the container's command.
#
# The repository's configset cannot be deployed as written: its planted defects stop a core from
# starting. So the collection is built from a copy that repairs them the way a real server might
# have, and differs in two more places. Each edit leaves one row in the drift view:
#
#   custom_text's tokenizer is a real one        -> Differs (the repository names a class that does not exist)
#   legacy's type is string                      -> Differs (the repository names a type nobody declares)
#   a manufacturer field is added                -> Only on server (and the dangling copyField becomes valid)
#   sku is removed                               -> Not deployed, the one row Apply can send
#
# It also adds _version_ and the plong type it needs, which SolrCloud requires and the repository's
# schema lacks. Those two read Only on server as well.
#
# The repository's own files are never touched. If an edit here stops matching the schema, the
# script fails rather than quietly deploying a different showcase.
#
# Same pattern as the image's own solr-demo script: start in the background, create, stop, then run
# in the foreground. The marker lives on the solr-data volume, so `down -v` starts over.

set -euo pipefail

MARKER=/var/solr/.products-created
CONF=/tmp/products-conf

if [ -f "$MARKER" ]; then
  echo "products was created on an earlier start; skipping"
else
  rm -rf "$CONF"
  cp -r /demo/conf "$CONF"
  schema="$CONF/managed-schema.xml"

  sed -i 's|<tokenizer class="com.example.MyTokenizerFactory"/>|<tokenizer class="solr.StandardTokenizerFactory"/>|' "$schema"
  sed -i '/<field name="legacy"/ s|type="discontinued"|type="string"|' "$schema"
  sed -i '/<field name="category"/ a\  <field name="manufacturer" type="string" indexed="true" stored="true"/>' "$schema"
  sed -i '/<field name="sku"/d' "$schema"
  # Not a showcase edit: SolrCloud refuses a collection whose schema has no _version_, and the
  # repository's schema declares none. Both show as Only on server.
  sed -i '/<fieldType name="string"/ a\  <fieldType name="plong" class="solr.LongPointField" docValues="true"/>' "$schema"
  sed -i '/<field name="id"/ a\  <field name="_version_" type="plong" indexed="false" stored="false"/>' "$schema"

  check() {
    if ! grep -q "$2" "$schema"; then
      echo "The schema no longer matches this script: $1. Update run-with-products.sh." >&2
      exit 1
    fi
  }
  check "custom_text's tokenizer was not replaced" 'tokenizer class="solr.StandardTokenizerFactory"'
  check "legacy's type was not replaced" '<field name="legacy" *type="string"'
  check "manufacturer was not added" '<field name="manufacturer"'
  check "_version_ was not added" '<field name="_version_"'
  check "plong was not added" '<fieldType name="plong"'
  if grep -q '<field name="sku"' "$schema"; then
    echo "The schema no longer matches this script: sku was not removed. Update run-with-products.sh." >&2
    exit 1
  fi

  solr start
  wait-for-solr.sh --max-attempts 24 --wait-seconds 5
  solr create -c products -d "$CONF"
  solr stop
  touch "$MARKER"
fi

exec solr-fg
