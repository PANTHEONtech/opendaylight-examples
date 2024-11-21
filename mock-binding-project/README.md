# ODL mock binding project

## First Steps

First, you can try to build the `netconf` and `controller` projects:

 ```sh
 mvn clean install              # Builds and installs artifacts into .m2, runs all checks and unit tests
 mvn clean install -DskipTests  # Same as above but skips unit tests
 mvn clean install -Pq          # Fast profile, skips all checks and unit tests, only compiles
 ```

Then, you can try running the Karaf distribution. You can find it in the `karaf` directory in the corresponding project.

 ```sh
 sh ./karaf/target/assembly/bin/karaf
 ```

Or for a clean run of Karaf:

 ```sh
 sh ./karaf/target/assembly/bin/karaf clean
 ```

This distribution will only have features from the project and its dependencies.  
If you need the full distribution, you need to build the `integration/distribution` project and use the distribution
from there.

## ODL Mock Binding Project

First, you will need a YANG model.  
You can get familiar with YANG [here](https://www.rfc-editor.org/rfc/rfc7950.html) and create your own model, or use an
existing one.

For the purpose of this tutorial, this simple YANG module is used:

 ```yang
 module example {
     yang-version 1.1;
     namespace "urn:opendaylight:example";
     prefix "ex";
     revision "2024-07-10";

     container cont {
         leaf l {
             type string;
         }
     }
 }
 ```

To implement your YANG model, put it into the [api module directory](mock-binding-project/api/src/main/yang).  
Then build the project; it should generate Java bindings(Java classes in
the [target directory](mock-binding-project/api/target/classes/org/opendaylight/yang/gen)).

You can then use those bindings with the `DataBroker` that is injected in the `impl` module to write/read data to/from
the datastore in the [MockBindingProvider](mock-binding-project/impl/src/main/java/pt/impl/MockBindingProvider.java)
#init() method.

**Example code for writing data to the datastore:**

 ```java
 // Create write transaction
 final WriteTransaction tx = dataBroker.newWriteOnlyTransaction();

 // Create data
 Cont data = new ContBuilder().setL("example string").build();

 // Create InstanceIdentifier for Cont container
 InstanceIdentifier<Cont> instanceIdentifier = InstanceIdentifier.create(Cont.class);

 // Put data to datastore and commit them
 tx.put(LogicalDatastoreType.CONFIGURATION, instanceIdentifier, data);
 tx.commit().get();
 ```

**Example code for reading data from the datastore:**

 ```java
 // Create read transaction
 final ReadTransaction rx = dataBroker.newReadOnlyTransaction();

 // Get optional data which can be empty
 FluentFuture<Optional<Cont>> future = rx.read(LogicalDatastoreType.CONFIGURATION, instanceIdentifier);
 Optional<Cont> optionalData = future.get();
         
 // Check if data is present and get them
 if (optionalData.isPresent()){
     Cont dataOut = optionalData.get();
 }
 ```

Build the project, run Karaf, and install the `mock-binding-project` feature to run your code:

 ```sh
 feature:install features-mock-binding-project
 ```

### Using RESTCONF

If you add the RESTCONF dependency to the Karaf POM, you can install the `odl-restconf-nb` feature and use RESTCONF
to manipulate data.

Add the dependency and rebuild the project:

 ```xml
 <dependency>
     <groupId>org.opendaylight.netconf</groupId>
     <artifactId>odl-restconf-nb</artifactId>
     <version>8.0.3</version>
     <classifier>features</classifier>
     <type>xml</type>
     <scope>runtime</scope>
 </dependency>
 ```

Run Karaf and install the features:

 ```sh
 feature:install features-mock-binding-project odl-restconf-nb
 ```

Then, you can use Postman to access and modify data:

For authentication, use Basic Auth with username and password set to "admin"  
or use the header `Authorization`with the value `Basic YWRtaW46YWRtaW4=`

**Writing data:**

To write data to the datastore, execute the request:

 ```http
 POST http://127.0.0.1:8181/rests/data/
 ```

With payload:

 ```xml
 <cont xmlns="urn:opendaylight:example">
     <l>Example content</l>
 </cont>
 ```

**Reading data:**

To read data from the datastore, execute the request:

 ```http
 GET http://127.0.0.1:8181/rests/data?content=config
 ```

Or for direct targeting of the container `Cont`:

 ```http
 GET http://127.0.0.1:8181/rests/data/example:cont?content=config
 ```

## Next Steps

As the next step, you can try the NETCONF test tool, which is part of the NETCONF project. More about the test tool
can be found in the [documentation](https://docs.opendaylight.org/projects/netconf/en/latest/testtool.html).
