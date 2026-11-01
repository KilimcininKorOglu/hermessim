// Compile-only stubs of hidden framework classes the app overrides callbacks with (see RootCallState).
plugins { `java-library` }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
