import Foundation
import HapiClient
import HapiProtocol
import Testing

private let emptyPageJSON = """
{"messages":[],"page":{"direction":"before","limit":200,"epoch":0,"reset":false,\
"nextBeforeSeq":null,"nextBeforeAt":null,"nextAfterSeq":null,"nextAfterAt":null,\
"snapshotHeadSeq":null,"snapshotHeadAt":null,"hasMore":false}}
"""

@Suite("Endpoint request construction")
struct EndpointRequestTests {
    private func bodyString(_ request: URLRequest?) -> String? {
        request?.httpBody.flatMap { String(data: $0, encoding: .utf8) }
    }

    @Test func messagesPageWithBeforeCursor() async throws {
        let token = freshJWT()
        let harness = try makeHarness(jwt: token)
        await harness.performer.enqueue(json: emptyPageJSON)

        let response = try await harness.client.messages(
            sessionId: "s 1",
            query: .before(seq: 42, at: 170, limit: 200)
        )
        #expect(response.messages.isEmpty)
        #expect(response.page.direction == .before)

        let request = await harness.performer.requests.first
        #expect(
            request?.url?.absoluteString
                == "\(testHubURLString)/api/sessions/s%201/messages?beforeAt=170&beforeSeq=42&limit=200"
        )
        #expect(request?.httpMethod == "GET")
        #expect(request?.value(forHTTPHeaderField: "Authorization") == "Bearer \(token)")
    }

    @Test func messagesPageWithAfterCursorAndEpoch() async throws {
        let harness = try makeHarness(jwt: freshJWT())
        await harness.performer.enqueue(json: emptyPageJSON)

        _ = try await harness.client.messages(
            sessionId: "abc",
            query: .after(seq: 10, at: 99, epoch: 3)
        )
        let request = await harness.performer.requests.first
        #expect(
            request?.url?.absoluteString
                == "\(testHubURLString)/api/sessions/abc/messages?afterAt=99&afterSeq=10&epoch=3"
        )
    }

    @Test func sendMessageWithDeliveryMode() async throws {
        let harness = try makeHarness(jwt: freshJWT())
        await harness.performer.enqueue(json: "{\"ok\":true}")

        try await harness.client.sendMessage(
            sessionId: "abc",
            text: "hi",
            localId: "L1",
            deliveryMode: .steer
        )
        let request = await harness.performer.requests.first
        #expect(request?.url?.absoluteString == "\(testHubURLString)/api/sessions/abc/messages")
        #expect(request?.httpMethod == "POST")
        #expect(request?.value(forHTTPHeaderField: "Content-Type") == "application/json")
        #expect(bodyString(request) == "{\"deliveryMode\":\"steer\",\"localId\":\"L1\",\"text\":\"hi\"}")
    }

    @Test func approveWithNestedAnswers() async throws {
        let harness = try makeHarness(jwt: freshJWT())
        await harness.performer.enqueue(json: "{\"ok\":true}")

        try await harness.client.approvePermission(
            sessionId: "abc",
            requestId: "r/1",
            PermissionApproveRequest(answers: ["q1": ["answers": ["a", "b"]]])
        )
        let request = await harness.performer.requests.first
        #expect(
            request?.url?.absoluteString
                == "\(testHubURLString)/api/sessions/abc/permissions/r%2F1/approve"
        )
        #expect(bodyString(request) == "{\"answers\":{\"q1\":{\"answers\":[\"a\",\"b\"]}}}")
    }

    @Test func approveWithFlatAnswersAndModeSwitch() async throws {
        let harness = try makeHarness(jwt: freshJWT())
        await harness.performer.enqueue(json: "{\"ok\":true}")

        try await harness.client.approvePermission(
            sessionId: "abc",
            requestId: "r1",
            PermissionApproveRequest(mode: .acceptEdits, answers: ["q1": ["a", "b"]])
        )
        let request = await harness.performer.requests.first
        #expect(
            bodyString(request)
                == "{\"answers\":{\"q1\":[\"a\",\"b\"]},\"mode\":\"acceptEdits\"}"
        )
    }

    @Test func setModelEncodesExplicitNull() async throws {
        let harness = try makeHarness(jwt: freshJWT())
        await harness.performer.enqueue(json: "{\"ok\":true}")
        try await harness.client.setModel(sessionId: "abc", model: nil)
        let first = await harness.performer.requests.first
        #expect(bodyString(first) == "{\"model\":null}")

        await harness.performer.enqueue(json: "{\"ok\":true}")
        try await harness.client.setModel(
            sessionId: "abc",
            model: .catalogReference(provider: "openai", modelId: "gpt-5")
        )
        let second = await harness.performer.requests.last
        #expect(bodyString(second) == "{\"model\":{\"modelId\":\"gpt-5\",\"provider\":\"openai\"}}")
    }

    @Test func spawnDiscriminatesOnType() async throws {
        let harness = try makeHarness(jwt: freshJWT())
        await harness.performer.enqueue(json: "{\"type\":\"success\",\"sessionId\":\"new-id\"}")
        let spawned = try await harness.client.spawnSession(
            machineId: "m1",
            SpawnRequest(directory: "/work/repo", agent: .claude, sessionType: .worktree, worktreeName: "wt")
        )
        #expect(spawned == .success(sessionId: "new-id"))
        let request = await harness.performer.requests.first
        #expect(request?.url?.absoluteString == "\(testHubURLString)/api/machines/m1/spawn")
        #expect(
            bodyString(request)
                == "{\"agent\":\"claude\",\"directory\":\"/work/repo\",\"sessionType\":\"worktree\",\"worktreeName\":\"wt\"}"
        )

        await harness.performer.enqueue(json: "{\"type\":\"error\",\"message\":\"no runner\"}")
        let failed = try await harness.client.spawnSession(machineId: "m1", SpawnRequest(directory: "/x"))
        #expect(failed == .error(message: "no runner"))
    }
}
