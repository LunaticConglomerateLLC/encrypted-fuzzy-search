# Build EFS from source

## Requirements

- JDK 21 or later. The project targets Java 21 bytecode and CI checks JDK 21 and 25.
- Git.
- Docker Compose v2 for PostgreSQL-backed functional tests and the database quickstart.

## Clone and build

```sh
git clone https://github.com/LunaticConglomerateLLC/encrypted-fuzzy-search.git
cd encrypted-fuzzy-search
./gradlew --no-daemon build
```

The Gradle wrapper pins the build version. Do not install project-specific proxy, certificate, mirror, or artifact credentials into the checkout. Keep those in your user-level Gradle configuration or your organization’s managed environment.

The build creates module JARs under each module's `build/libs/` directory. The current repository does not publish these JARs to a public or private Maven repository. To consume EFS in another build, first arrange a reviewed artifact publication or include the source modules in the application’s own Gradle build. Do not invent Maven coordinates from the project name.

## Run the full test suite

The normal `check` task includes PostgreSQL functional tests, so start the disposable database first:

```sh
docker compose -f compose.test.yaml up --detach --wait
./gradlew --no-daemon clean check
docker compose -f compose.test.yaml down --volumes --remove-orphans
```

The database binds to loopback and uses test-only credentials. The Compose service stores data in its disposable container layer. Remove the volume after testing as shown above.

Run only the database functional tests with:

```sh
docker compose -f compose.test.yaml up --detach --wait
./gradlew --no-daemon :postgres-adapter:functionalTest
docker compose -f compose.test.yaml down --volumes --remove-orphans
```

See [PostgreSQL integration](../guides/postgresql.md) for the schema and test setup. For the exact Gradle modules, see the [architecture and module guide](../reference/architecture.md).
