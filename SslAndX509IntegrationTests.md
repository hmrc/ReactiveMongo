# Testing SSL/TLS connection and X509 authentication locally

## Creating certificates for SSL and X509 authentication

If you want just TLS and not x509 use [this script](mongo_tls_setup.sh).

Note: This following instructions assumes you are using either MongoDB version 3.2 or 3.4 and has been tested for both. Prior and later versions may require different configuration files.

First of all, the certification creation is borrowed from the [following blog post](http://demarcsek92.blogspot.co.uk/2014/05/mongodb-ssl-setup.html) and is adapted for our tests.

To make the instructions as simple as possible, I will be using the `~/.mongossl` directory to put all the certificates, keys, logs, configuration and WiredTiger data in that directory. The instructions will allow you to go back to using your machine's default MongoDB instance without any of this work changing or affecting it.

#### Step 1 - Creating the Server Certificates

First of all we are going to create a server certificate. The certificate email will be `test@test.com` and password would be `test`. The number of days set in the certificates will be `365` assuming you'll be reusing the certificate often for local development. If you want a short lived certificate, you can change that. We will be doing this in the `~/.mongossl` directory.

```sh
openssl req -new -x509 -days 365 -out mongodb-cert.crt -keyout mongodb-cert.key
```

Be sure to use the same settings as bellow, as this will be important for generating the correct RFC2253 subject name. We'll want to have the same `Country Name`, `Locality Name` and `Common Name` to smoothen testing and usage for local development.

```sh
Generating a 2048 bit RSA private key
......+++
........+++
writing new private key to 'mongodb-cert.key'
Enter PEM pass phrase:
Verifying - Enter PEM pass phrase:
-----
You are about to be asked to enter information that will be incorporated
into your certificate request.
What you are about to enter is what is called a Distinguished Name or a DN.
There are quite a few fields but you can leave some blank
For some fields there will be a default value,
If you enter '.', the field will be left blank.
-----
Country Name (2 letter code) [AU]:UK
State or Province Name (full name) [Some-State]:LONDON
Locality Name (eg, city) []:LONDON
Organization Name (eg, company) [Internet Widgits Pty Ltd]:TEST
Organizational Unit Name (eg, section) []:TEST
Common Name (e.g. server FQDN or YOUR name) []:127.0.0.1
Email Address []:test@test.com
```

We then want to create the server `pem` file using the following command:

```sh
cat mongodb-cert.key mongodb-cert.crt > mongodb.pem
```

NOTE: We MUST use `127.0.0.1` as the `CN` because of Server Name Identification (SNI) and self signed local certifications. If you have anything else for a self-signed certificate, MongoDB will reject that certificate and will not launch with it.

#### Step 2 - Create the Client Certificate

Similar to Step 1, we're going to create the client certificate. Staying in the same directory, execute the following command: 

```sh
openssl req -new -x509 -days 365 -out client-cert.crt -keyout client-cert.key
```
We will use a different email (`testclient@testclient.com`), different passphrase(`test_client`) and different `O` and `OU` for our client certificate so that we have enough differentiators. 

Again, be sure you use the same fields as we do below bearing in mind the passphrase is `testclient`:

```sh
Generating a 2048 bit RSA private key
...........................................................................................+++
...........................................+++
writing new private key to 'client-cert.key'
Enter PEM pass phrase:
Verifying - Enter PEM pass phrase:
-----
You are about to be asked to enter information that will be incorporated
into your certificate request.
What you are about to enter is what is called a Distinguished Name or a DN.
There are quite a few fields but you can leave some blank
For some fields there will be a default value,
If you enter '.', the field will be left blank.
-----
Country Name (2 letter code) [AU]:UK
State or Province Name (full name) [Some-State]:LONDON
Locality Name (eg, city) []:LONDON
Organization Name (eg, company) [Internet Widgits Pty Ltd]:TEST_CLIENT
Organizational Unit Name (eg, section) []:TEST_CLIENT
Common Name (e.g. server FQDN or YOUR name) []:127.0.0.1
Email Address []:testclient@testclient.com
```

Finally we want to create the `PEM` file for the client

```sh
cat client-cert.key client-cert.crt > client.pem
```

#### Step 3 - Create the configuration

Again, staying in the same directory, we need to create the configuration for MongoDB to use our certificates. I am assuming that the username is `bob` and we're using macOS in the configuration. If you are using a Linux based operating system, your home directory would be under `/home/<your_username>`. Either way, please adjust the configuration to be the fully resolved URI to your `.mongossl` directory. Save this file as `mongodb-ssl.conf`


```yaml
systemLog:
  destination: file
  path: /Users/bob/.mongossl/log/mongo.log
  logAppend: true
storage:
  dbPath: /Users/bob/.mongossl/data
net:
  bindIp: 127.0.0.1
  ssl:
    mode: requireSSL # do not permit non-SSL connections
    PEMKeyFile: /Users/bob/.mongossl/mongodb.pem # PEM key file containing the private key of the server
    PEMKeyPassword: test # password (to decrypt private key)
    CAFile: /Users/bob/.mongossl/client.pem # PEM file containing CA certificate
    allowInvalidCertificates: true

#security:
 #clusterAuthMode: x509
```
NOTE: notice how the `clusterAuthMode` is commented out, if you wish to use SSL/TLS connections without X509 authentication, leave this commented out. If you wish to use `X509` authentication, uncomment this out. HOWEVER, before using X509 authentication, make sure you add the user to the `$external` auth database. This will be shown below.

Make sure the `~/.mongossl/data` directory is created and the `~/.mongossl/log/mongo.log` file and its directory is created.

If you want the logs to be printed out to your terminal, then remove the `systemLog.path` field.

#### Step 4 - Launch MongoDB 

In a separate terminal window, launch `MongoDB` with the following command:

```sh
mongod --config mongodb-ssl.conf
```

and finally to make sure that MongoDB has launched, in a separate window, tail the logs: `tail -f ~/.mongossl/log/mongo.log`. If all goes well, you should see logs similar to this:

```sh
2017-11-14T13:54:51.537+0000 I CONTROL  [initandlisten] MongoDB starting : pid=10439 port=27017 dbpath=/Users/bob/.mongossl/data 64-bit host=Bobs-MacBook-Pro.local
2017-11-14T13:54:51.537+0000 I CONTROL  [initandlisten] db version v3.2.11
2017-11-14T13:54:51.537+0000 I CONTROL  [initandlisten] git version: 009580ad490190ba33d1c6253ebd8d91808923e4
2017-11-14T13:54:51.537+0000 I CONTROL  [initandlisten] OpenSSL version: OpenSSL 1.0.2l  25 May 2017
2017-11-14T13:54:51.537+0000 I CONTROL  [initandlisten] allocator: system
2017-11-14T13:54:51.537+0000 I CONTROL  [initandlisten] modules: none
2017-11-14T13:54:51.537+0000 I CONTROL  [initandlisten] build environment:
2017-11-14T13:54:51.537+0000 I CONTROL  [initandlisten]     distarch: x86_64
2017-11-14T13:54:51.537+0000 I CONTROL  [initandlisten]     target_arch: x86_64
2017-11-14T13:54:51.537+0000 I CONTROL  [initandlisten] options: { config: "mongodb-ssl.conf", net: { bindIp: "127.0.0.1", ssl: { CAFile: "/Users/bob/.mongossl/client.pem", PEMKeyFile: "/Users/bob/.mongossl/mongodb.pem", PEMKeyPassword: "<password>", allowInvalidCertificates: true, mode: "requireSSL" } }, security: { clusterAuthMode: "x509" }, storage: { dbPath: "/Users/bob/.mongossl/data" }, systemLog: { destination: "file", logAppend: true, path: "/Users/bob/.mongossl/log/mongo.log" } }
2017-11-14T13:54:51.538+0000 I -        [initandlisten] Detected data files in /Users/bob/.mongossl/data created by the 'wiredTiger' storage engine, so setting the active storage engine to 'wiredTiger'.
2017-11-14T13:54:51.538+0000 I STORAGE  [initandlisten] wiredtiger_open config: create,cache_size=9G,session_max=20000,eviction=(threads_max=4),config_base=false,statistics=(fast),log=(enabled=true,archive=true,path=journal,compressor=snappy),file_manager=(close_idle_time=100000),checkpoint=(wait=60,log_size=2GB),statistics_log=(wait=0),
2017-11-14T13:54:51.983+0000 I FTDC     [initandlisten] Initializing full-time diagnostic data capture with directory '/Users/bob/.mongossl/data/diagnostic.data'
2017-11-14T13:54:51.983+0000 I NETWORK  [HostnameCanonicalizationWorker] Starting hostname canonicalization worker
2017-11-14T13:54:51.984+0000 I NETWORK  [initandlisten] waiting for connections on port 27017 ssl
```

#### Step 5 - Validate SSL/TLS connection works by connecting with the MongoDB client

Now that we have got our MongoDB running with SSL, we want to connect to it with the client. Here is how:

```sh
mongo  --ssl --sslCAFile ~/.mongossl/mongodb.pem --sslPEMKeyFile ~/.mongossl/client.pem --sslPEMKeyPassword test_client
```

You should be in. If you execute the `show dbs` command in MongoDB, you should be able to to see a list of your databases. 

NOTE: You may get the following error if you have `clusterAuthMode` set to `x509` in your configuration and you have not authenticated against the certificate:

```sh
2017-11-14T14:16:40.943+0000 E QUERY    [thread1] Error: listDatabases failed:{
    "ok" : 0,
    "errmsg" : "not authorized on admin to execute command { listDatabases: 1.0 }",
    "code" : 13
} :
_getErrorWithCode@src/mongo/shell/utils.js:25:13
Mongo.prototype.getDBs@src/mongo/shell/mongo.js:62:1
shellHelper.show@src/mongo/shell/utils.js:761:19
shellHelper@src/mongo/shell/utils.js:651:15
@(shellhelp2):1:1
```

#### Step 6 - Create the PKCS12 Keystore for Client Certificate and Key

Going back to the original `~/.mongossl` directory and leaving our MongoDB running in the background, as we'll be connecting to it later, run the following command to get the `PKCS12` keystore. We need this to create the the Java keystore in the next step. Execute the following command:

```sh
openssl pkcs12 -export -out ./keystore.p12 -inkey ./client.pem -in ./client.pem \
     -passin pass:test_client -passout pass:test_client
```

Again, this assumes you kept the username and password from the initial steps

#### Step 7 - Create the Java Keystore (JKS) File

Now we create the JKS file so that we can pass it in as a parameter to our program. Execute the following command:

```sh
keytool -importkeystore  -srcstoretype PKCS12 \
            -srckeystore "keystore.p12" \
            -destkeystore ./keystore.jks \
            -storepass test_client -srcstorepass test_client
```

`keytool` should have been installed if you installed the JDK for your operating system. 

#### Step 8 - Pass the Keystore into SBT

Now you need to pass in three VM parameters to any application that runs ReactiveMongo to be able to get SSL/TLS connection working, these parameters are:

- javax.net.ssl.keyStore
    + This is to specify the location of the `keystore.jks` that we created
- javax.net.ssl.keyStorePassword
    + This is to specify the keystore password
- javax.net.ssl.keyStoreType
    + This is to specify the keystore type. Must be set to JKS.

#### Step 9 - Creating a User to Enable X509 Authentication in MongoDB's Auth DB

Now that we have SSL/TLS working, we can now use X509 authentication. But before we do that, we must first enable a user for X509 authentication. Use the following command to create the user and allow it to work with the default db `test`, `test-team-and-repositories` where a spike includes a working x509 test and `specs2-test-x509-auth` for the `X509DriverSpec` to do integration tests around the driver. I also give it `root` access to the `admin` database for local development, this SHOULD NOT happen for production. The following must executed inside a `mongo` client shell:

```javascript
db.getSiblingDB("$external").runCommand(
  {
    createUser: "emailAddress=testclient@testclient.com,CN=127.0.0.1,OU=TEST_CLIENT,O=TEST_CLIENT,L=LONDON,ST=LONDON,C=UK",
    roles: [
             { role: 'readWrite', db: 'test' },
             { role: 'readWrite', db: 'test-teams-and-repositories' },
             { role: 'dbOwner', db: 'specs2-test-x509-auth' },
             { role: 'root', db: 'admin' }
           ],
    writeConcern: { w: "majority" , wtimeout: 5000 }
  }
)
```

#### Step 10 - Enable X509 authentication.

Now let's go back to our `mongodb-ssl.conf` and uncomment out `security` and `clusterAuthMode` configurations from the earlier created `mongodb-ssl.conf` file. Then, restart MongoDB with the updated conf.

If all goes well, connect to MongoDB using the command from Step 5 and once you are in, try and run `show dbs`. That command should fail. Now authenticate using the following command:

```javascript
db.getSiblingDB("$external").auth(
  {
    mechanism: "MONGODB-X509",
    user: "emailAddress=testclient@testclient.com,CN=127.0.0.1,OU=TEST_CLIENT,O=TEST_CLIENT,L=LONDON,ST=LONDON,C=UK"
  }
)
```

From there, run `show dbs`. It should work again. Also, maybe try creating a document in `test` database. First you would run `use test` then you would issue a command such as `db.testCol.insert({k: "v"})` and it should work.

#### Step 11 - Running X509DriverSpec 

Finally to validate all this works, we run the following command to test X509 authentication with ReactiveMongo. Again, be sure to change the location of where your keystore is based on where your home directory is.

```sh
sbt -Djavax.net.ssl.keyStore=/Users/bob/.mongossl/keystore.jks -Djavax.net.ssl.keyStorePassword=test_client -Djavax.net.ssl.keyStoreType=JKS ";project ReactiveMongo; test-only *X509DriverSpec"

```
