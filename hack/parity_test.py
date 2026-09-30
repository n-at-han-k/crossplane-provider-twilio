#!/usr/bin/env python3
"""Does hack/parity.py still agree with Twilio's generator?

`parity.py` transcribes four functions from twilio-oai-generator into Python so
that the resource selection can be checked without running the generator. A
transcription is only worth having while it is exact, and the way it stops
being exact is silent -- a rule drifts, the catalog still builds, and the diff
it produces is wrong in a direction nobody notices.

So the vectors below are not invented here. They are lifted from Twilio's own
unit tests, in the submodule:

  reference/twilio-oai-generator/src/test/java/com/twilio/oai/StringHelperTest.java
  reference/twilio-oai-generator/src/test/java/com/twilio/oai/PathUtilsTest.java

which is the one genuinely reusable thing in either of Twilio's repositories:
their form-encoder and schema-marshalling tests cover code this provider does
not have, because it uses twilio-go rather than a client of its own, and their
two resource tests are Terraform acceptance tests for products this provider
does not yet cover. See PLAN.md.

    python3 hack/parity_test.py
"""
import sys

from parity import (
    clean_path_and_remove_first_element,
    remove_extension,
    remove_path_param_ids,
    remove_trailing_path_param,
    to_snake,
)

# StringHelperTest.toSnakeCase. Every one of these is a case the two-pass
# ToSnakeCase inside terraform-provider-twilio gets WRONG, which is why
# parity.py transcribes the generator's four-pass one instead:
# `callbackURL` and `AwsS3Url` are the acronym and digit boundaries.
SNAKE = [
    ("SomeA2PThing", "some_a2p_thing"),
    ("Psd2Enabled", "psd2_enabled"),
    ("AwsS3Url", "aws_s3_url"),
    ("callbackURL", "callback_url"),
    # Ours, not theirs: the case that misnames 11 resources if the fourth pass
    # is missing, and the one that made the first parity run report 11 missing
    # and 11 extra.
    ("SIPDomains", "sip_domains"),
]

# PathUtilsTest, with Twilio's own inputs.
PATHS = [
    (remove_extension, "some/path/{param1}/with/extension.ext", "some/path/{param1}/with/extension"),
    (remove_extension, "some/path", "some/path"),
    (remove_trailing_path_param,
     "some/path/with/{multiple}/trailing/{param}",
     "some/path/with/{multiple}/trailing"),
    (clean_path_and_remove_first_element,
     "/some/path/with/{params}/and/extension.ext",
     "/path/with/and/extension"),
]

# How the superclass keys a resource, which is the composition parity.py uses
# and no single upstream test covers. Twilio's own example: the collection and
# its member path must key on the same thing, or one resource becomes two.
RESOURCE_KEYS = [
    ("/2010-04-01/Accounts/{AccountSid}/Addresses.json",
     "/2010-04-01/Accounts/{}/Addresses"),
    ("/2010-04-01/Accounts/{AccountSid}/Addresses/{Sid}.json",
     "/2010-04-01/Accounts/{}/Addresses"),
]


def main():
    failures = []

    for given, want in SNAKE:
        got = to_snake(given)
        if got != want:
            failures.append("to_snake(%r) = %r, want %r" % (given, got, want))

    for func, given, want in PATHS:
        got = func(given)
        if got != want:
            failures.append("%s(%r) = %r, want %r" % (func.__name__, given, got, want))

    for given, want in RESOURCE_KEYS:
        got = remove_path_param_ids(remove_trailing_path_param(remove_extension(given)))
        if got != want:
            failures.append("resource key of %r = %r, want %r" % (given, got, want))

    for failure in failures:
        print("FAIL", failure)

    total = len(SNAKE) + len(PATHS) + len(RESOURCE_KEYS)
    print("%d/%d agree with twilio-oai-generator's own tests" % (total - len(failures), total))

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
