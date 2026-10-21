import groovy.json.JsonSlurper

// Called for every response. Failures never print or copy response bodies.
def reject = { String reason ->
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage(reason)
    prev.setSuccessful(false)
}

try {
    if (prev.getResponseCode() != '200' || !prev.isSuccessful()) {
        reject('Following HTTP request did not complete successfully with status 200.')
        return
    }

    // The fixture is immutable for this run; cache its parsed ID set per thread.
    Set<String> expectedIds = vars.getObject('following_expected_id_set') as Set<String>
    if (expectedIds == null) {
        String configuredIds = props.getProperty('expected_user_ids', '')
        List<String> parts = configuredIds.split(',', -1).collect { it.trim() }
        if (parts.size() != 10 || parts.any { !(it ==~ /^[1-9][0-9]{0,18}$/) || Long.parseLong(it) <= 0L }) {
            reject('Following fixture must declare exactly ten positive Long user IDs.')
            return
        }
        expectedIds = new HashSet<String>(parts)
        if (expectedIds.size() != 10) {
            reject('Following fixture user IDs must be distinct.')
            return
        }
        vars.putObject('following_expected_id_set', expectedIds)
    }

    def page = new JsonSlurper().parseText(prev.getResponseDataAsString())
    if (!(page instanceof Map) || !(page.success instanceof Boolean) || page.success != true) {
        reject('Following response must be a successful JSON page.')
        return
    }
    if (!(page.totalCount instanceof Number) || page.totalCount != 10
            || !(page.pageNo instanceof Number) || page.pageNo != 1
            || !(page.pageSize instanceof Number) || page.pageSize != 10) {
        reject('Following response must have totalCount=10, pageNo=1 and pageSize=10.')
        return
    }
    if (!(page.data instanceof List) || page.data.size() != 10) {
        reject('Following response must contain ten user objects, never an empty page.')
        return
    }

    Set<String> actualIds = new HashSet<String>()
    for (def item : page.data) {
        if (!(item instanceof Map) || item.userId == null) {
            reject('Following response contains an invalid user object.')
            return
        }
        String userId = item.userId.toString()
        if (!(userId ==~ /^[1-9][0-9]{0,18}$/) || Long.parseLong(userId) <= 0L || !actualIds.add(userId)) {
            reject('Following response contains an invalid or duplicate user ID.')
            return
        }
        if (!(item.nickname instanceof String) || item.nickname.trim().isEmpty()) {
            reject('Following response contains a user without a valid nickname.')
            return
        }
        if (!(item.introduction instanceof String) || item.introduction.trim().isEmpty()) {
            reject('Following fixture user must retain its nonempty introduction.')
            return
        }
        if (item.avatar != null && !(item.avatar instanceof String)) {
            reject('Following response contains an invalid avatar field.')
            return
        }
    }
    if (actualIds != expectedIds) {
        reject('Following response user-ID set does not match the owned fixture.')
    }
} catch (Exception ignored) {
    // JSON/number parsers can include input in exception messages; keep it private.
    reject('Following JSON validation or fixture-ID parsing failed.')
}
