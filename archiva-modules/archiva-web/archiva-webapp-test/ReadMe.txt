ReadMe
----------
This module historically ran Selenium / TestNG integration tests against a
running Archiva instance. The previous Maven pom drove them via the
selenium-maven-plugin / exec-maven-plugin combo, and tests live under
src/test/testng.

Under the Bazel build these tests are not wired in (see the note in this
directory's BUILD.bazel) -- the JS/webdriver toolchain is a separate ecosystem
that hasn't been ported to Bazel here. To run the tests you'll need to drive
them manually against a running Archiva:

  - Build and start Archiva from the repository root, e.g.
      bazel build //archiva-jetty:archiva-jetty-dist
      sh ./jetty.sh
  - Configure an admin user for Archiva (match the values in
    src/test/resources/testng.properties).
  - Adjust src/test/resources/testng.properties as needed.
  - Run the TestNG suite under src/test/testng with the test runner of your
    choice (e.g. an IDE, or a TestNG launcher you provide yourself).

Internet Explorer and Safari users must disable their popup blockers. Using
*iexplore as the browser requires running as an Administrator on Windows
7/Vista, or alternatively you can use *iexploreproxy.

IMPORTANT:

When writing Selenium tests for artifact upload, please avoid using the "test"
syllable/word for the groupId or artifactId
(ex. test.group:testAddArtifactValidValues:1.0) as this is used for the search
tests. The tests explicitly assert the returned number of hits for searching an
artifact with a groupId or artifactId containing the word "test", so if you
upload or add a new artifact which has the term "test", the number of hits will
be different and the search tests will fail.

See org.apache.archiva.web.test.SearchTest.java or read the related thread
discussion at
http://old.nabble.com/Selenium-tests-failure-in-trunk-td27830786.html
