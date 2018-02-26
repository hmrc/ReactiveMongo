#!/usr/bin/env bash


if [ $# -lt 1 ]; then
    echo "Please provide a directory to store generated files"
    exit 1
fi

MONGO_DIR=$1
PASS=test_test

pushd $MONGO_DIR > /dev/null

echo "all files will be created in $MONGO_DIR"
echo "creating a new server certificate"
echo

MONGO_DB_CERT=mongodb-cert.crt

openssl req -new -x509 -days 365 -out $MONGO_DB_CERT -keyout mongodb-cert.key \
   -subj "/C=UK/ST=London/L=London/O=Test/CN=127.0.0.1/OU=Test" -passout pass:$PASS

cat $MONGO_DB_CERT mongodb-cert.key > mongodb.pem

echo "creating necessary directories"
mkdir -p $MONGO_DIR/log
mkdir -p $MONGO_DIR/data

echo "Generating java truststore"
keytool -importcert \
        -trustcacerts -file $MONGO_DB_CERT \
        -alias mongodb \
        -storepass $PASS \
        -keystore truststore.jks \
        -noprompt

echo "creating mongod config file"
cat << EOF > mongodb-ssl.conf
storage:
  dbPath: $MONGO_DIR/data
net:
  bindIp: 127.0.0.1
  ssl:
    mode: requireSSL # do not permit non-SSL connections
    PEMKeyFile: $MONGO_DIR/mongodb.pem # PEM key file containing the private key of the server
    PEMKeyPassword: $PASS # to decrypt private key
EOF

echo
echo "Please run the following command to start mongod with TLS required:"
echo "mongod --config $MONGO_DIR/mongodb-ssl.conf"
echo
echo "You can connect to it from mongo cmd client:"
echo "mongo  --ssl --sslCAFile $MONGO_DIR/$MONGO_DB_CERT"

echo
echo "You can run your scala app with"
echo "sbt -Djavax.net.ssl.trustStore=$MONGO_DIR/truststore.jks -Djavax.net.ssl.trustStorePassword=$PASS run"
echo

popd > /dev/null



