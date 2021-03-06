/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.aws.deploy.ops.securitygroup

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.ec2.model.IpPermission
import com.amazonaws.services.ec2.model.SecurityGroup
import com.amazonaws.services.ec2.model.UserIdGroupPair
import com.netflix.spinnaker.clouddriver.aws.TestCredential
import com.netflix.spinnaker.clouddriver.aws.deploy.description.UpsertSecurityGroupDescription
import com.netflix.spinnaker.clouddriver.aws.deploy.description.UpsertSecurityGroupDescription.IpIngress
import com.netflix.spinnaker.clouddriver.aws.deploy.description.UpsertSecurityGroupDescription.SecurityGroupIngress
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.securitygroup.SecurityGroupLookupFactory.SecurityGroupUpdater
import com.netflix.spinnaker.clouddriver.aws.security.NetflixAmazonCredentials
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import spock.lang.Specification
import spock.lang.Subject

class UpsertSecurityGroupAtomicOperationUnitSpec extends Specification {
  def setupSpec() {
    TaskRepository.threadLocalTask.set(Mock(Task))
  }

  def description = new UpsertSecurityGroupDescription(
    credentials: Stub(NetflixAmazonCredentials) {
      getName() >> "test"
    },
    vpcId: "vpc-123",
    name: "foo",
    description: "desc",
    securityGroupIngress: []
  )


  final securityGroupLookup = Mock(SecurityGroupLookupFactory.SecurityGroupLookup)

  final securityGroupLookupFactory = Stub(SecurityGroupLookupFactory) {
    getInstance(_) >> securityGroupLookup
  }

  @Subject
    op = new UpsertSecurityGroupAtomicOperation(description)


  def setup() {
    op.securityGroupLookupFactory = securityGroupLookupFactory
  }

  void "non-existent security group should be created"() {
    when:
    op.operate([])

    then:
    1 * securityGroupLookup.getSecurityGroupByName("test", "foo", "vpc-123") >> Optional.empty()

    then:
    1 * securityGroupLookup.createSecurityGroup(description) >> new SecurityGroupUpdater(null, null)
    0 * _
  }

  void "ingress minimally requires an accountId and security group id"() {
    final createdSecurityGroup = Mock(SecurityGroupUpdater)
    description.securityGroupIngress = [
      new SecurityGroupIngress(id: "id-bar", accountName: "prod", accountId: "accountId2", name: "bar", startPort: 111, endPort: 112, ipProtocol: "tcp", vpcId: "vpc-456")
    ]

    when:
    op.operate([])

    then:
    1 * securityGroupLookup.accountIdExists("accountId2") >> false

    then:
    1 * securityGroupLookup.getSecurityGroupByName("test", "foo", "vpc-123") >> Optional.empty()
    1 * securityGroupLookup.createSecurityGroup(description) >> createdSecurityGroup
    1 * createdSecurityGroup.getSecurityGroup()

    then:
    1 * createdSecurityGroup.addIngress([
      new IpPermission(ipProtocol: "tcp", fromPort: 111, toPort: 112, userIdGroupPairs: [
        new UserIdGroupPair(userId: "accountId2", groupId: "id-bar", vpcId: "vpc-456")
      ])
    ])
    0 * _
  }

  void "ingress by group name requires a known account id"() {
    final createdSecurityGroup = Mock(SecurityGroupUpdater)
    description.securityGroupIngress = [
      new SecurityGroupIngress(accountName: "prod", accountId: "accountId2", name: "bar", startPort: 111, endPort: 112, ipProtocol: "tcp", vpcId: "vpc-456")
    ]

    when:
    op.operate([])

    then:
    1 * securityGroupLookup.accountIdExists("accountId2") >> true
    1 * securityGroupLookup.getAccountNameForId("accountId2") >> "prod"
    1 * securityGroupLookup.getSecurityGroupByName("prod", "bar", "vpc-456") >> Optional.of(new SecurityGroupUpdater(
      new SecurityGroup(groupId: "id-bar"),
      null
    ))

    then:
    1 * securityGroupLookup.getSecurityGroupByName("test", "foo", "vpc-123") >> Optional.empty()
    1 * securityGroupLookup.createSecurityGroup(description) >> createdSecurityGroup
    1 * createdSecurityGroup.getSecurityGroup()

    then:
    1 * createdSecurityGroup.addIngress([
      new IpPermission(ipProtocol: "tcp", fromPort: 111, toPort: 112, userIdGroupPairs: [
        new UserIdGroupPair(userId: "accountId2", groupId: "id-bar", vpcId: "vpc-456")
      ])
    ])
    0 * _

  }

  void "ingress by group name fails with unknown account id"() {
    description.securityGroupIngress = [
      new SecurityGroupIngress(accountName: "prod", accountId: "accountId2", name: "bar", startPort: 111, endPort: 112, ipProtocol: "tcp", vpcId: "vpc-456")
    ]

    when:
    op.operate([])

    then:
    1 * securityGroupLookup.accountIdExists("accountId2") >> false
    thrown(IllegalStateException)
  }


  void "non-existent security group should be created with ingress"() {
    final createdSecurityGroup = Mock(SecurityGroupUpdater)
    final testCred = TestCredential.named("test")
    description.securityGroupIngress = [
      new SecurityGroupIngress(name: "bar", startPort: 111, endPort: 112, ipProtocol: "tcp")
    ]

    when:
    op.operate([])

    then:
    1 * securityGroupLookup.getCredentialsForName("test") >> testCred
    1 * securityGroupLookup.getSecurityGroupByName("test", "bar", "vpc-123") >> Optional.of(new SecurityGroupUpdater(
      new SecurityGroup(groupId: "id-bar"),
      null
    ))

    then:
    1 * securityGroupLookup.getSecurityGroupByName("test", "foo", "vpc-123") >> Optional.empty()

    then:
    1 * securityGroupLookup.createSecurityGroup(description) >> createdSecurityGroup
    1 * createdSecurityGroup.getSecurityGroup()

    then:
    1 * createdSecurityGroup.addIngress([
      new IpPermission(ipProtocol: "tcp", fromPort: 111, toPort: 112, userIdGroupPairs: [
        new UserIdGroupPair(userId: testCred.accountId, groupId: "id-bar")
      ])
    ])
    0 * _
  }

  void "non-existent security group that is found on create should be updated"() {
    final existingSecurityGroup = Mock(SecurityGroupUpdater)
    def testCred = TestCredential.named("test")
    description.securityGroupIngress = [
      new SecurityGroupIngress(name: "bar", startPort: 111, endPort: 112, ipProtocol: "tcp"),
      new SecurityGroupIngress(name: "bar", startPort: 211, endPort: 212, ipProtocol: "tcp")
    ]

    when:
    op.operate([])

    then:
    2 * securityGroupLookup.getCredentialsForName("test") >> testCred
    2 * securityGroupLookup.getSecurityGroupByName("test", "bar", "vpc-123") >> Optional.of(new SecurityGroupUpdater(
      new SecurityGroup(groupId: "id-bar"),
      null
    ))

    then:
    1 * securityGroupLookup.getSecurityGroupByName("test", "foo", "vpc-123") >> Optional.empty()

    then:
    1 * securityGroupLookup.createSecurityGroup(description) >> {
      throw new AmazonServiceException("").with {
        it.errorCode = "InvalidGroup.Duplicate"
        it
      }
    }
    1 * securityGroupLookup.getSecurityGroupByName("test", "foo", "vpc-123") >> Optional.of(existingSecurityGroup)
    1 * existingSecurityGroup.getSecurityGroup() >> new SecurityGroup(ipPermissions: [
      new IpPermission(fromPort: 211, toPort: 212, ipProtocol: "tcp", userIdGroupPairs: [
        new UserIdGroupPair(userId: testCred.accountId, groupId: "id-bar")
      ])
    ])

    then:
    1 * existingSecurityGroup.addIngress([
      new IpPermission(ipProtocol: "tcp", fromPort: 111, toPort: 112, userIdGroupPairs: [
        new UserIdGroupPair(userId: testCred.accountId, groupId: "id-bar")
      ])
    ])
    0 * _
  }

  void "existing security group should be unchanged"() {
    when:
    op.operate([])

    then:
    1 * securityGroupLookup.getSecurityGroupByName("test", "foo", "vpc-123") >>
      Optional.of(new SecurityGroupUpdater(new SecurityGroup(groupId: "id-bar"), null))
    0 * _
  }

  void "existing security group should be updated with ingress by name"() {
    final existingSecurityGroup = Mock(SecurityGroupUpdater)
    def testCred = TestCredential.named("test")
    description.securityGroupIngress = [
      new SecurityGroupIngress(name: "bar", startPort: 111, endPort: 112, ipProtocol: "tcp")
    ]

    when:
    op.operate([])

    then:
    1 * securityGroupLookup.getCredentialsForName("test") >> testCred
    1 * securityGroupLookup.getSecurityGroupByName("test", "bar", "vpc-123") >>
      Optional.of(new SecurityGroupUpdater(new SecurityGroup(groupId: "id-bar"), null))

    then:
    1 * securityGroupLookup.getSecurityGroupByName("test", "foo", "vpc-123") >> Optional.of(existingSecurityGroup)
    1 * existingSecurityGroup.getSecurityGroup() >> new SecurityGroup(groupId: "123", ipPermissions: [])

    then:
    1 * existingSecurityGroup.addIngress([
      new IpPermission(ipProtocol: "tcp", fromPort: 111, toPort: 112, userIdGroupPairs: [
        new UserIdGroupPair(userId: testCred.accountId, groupId: "id-bar")
      ])
    ])
    0 * _
  }

  void "existing security group should be updated with ingress by id"() {
    final existingSecurityGroup = Mock(SecurityGroupUpdater)
    def testCred = TestCredential.named("test")
    description.securityGroupIngress = [
      new SecurityGroupIngress(id: "id-bar", startPort: 111, endPort: 112, ipProtocol: "tcp")
    ]

    when:
    op.operate([])

    then:
    1 * securityGroupLookup.getCredentialsForName("test") >> testCred

    then:
    1 * securityGroupLookup.getSecurityGroupByName("test", "foo", "vpc-123") >> Optional.of(existingSecurityGroup)
    1 * existingSecurityGroup.getSecurityGroup() >> new SecurityGroup(groupId: "123", ipPermissions: [])

    then:
    1 * existingSecurityGroup.addIngress([
      new IpPermission(ipProtocol: "tcp", fromPort: 111, toPort: 112, userIdGroupPairs: [
        new UserIdGroupPair(userId: testCred.accountId, groupId: "id-bar")
      ])
    ])
    0 * _
  }

  void "existing security group should be updated with ingress from another account"() {
    final existingSecurityGroup = Mock(SecurityGroupUpdater)
    def prodCred = TestCredential.named("prod")
    description.securityGroupIngress = [
      new SecurityGroupIngress(accountName: "prod", name: "bar", startPort: 111, endPort: 112, ipProtocol: "tcp")
    ]

    when:
    op.operate([])

    then:
    1 * securityGroupLookup.getCredentialsForName("prod") >> prodCred
    1 * securityGroupLookup.getSecurityGroupByName("prod", "bar", "vpc-123") >>
      Optional.of(new SecurityGroupUpdater(new SecurityGroup(groupId: "id-bar"), null))

    then:
    1 * securityGroupLookup.getSecurityGroupByName("test", "foo", "vpc-123") >> Optional.of(existingSecurityGroup)
    1 * existingSecurityGroup.getSecurityGroup() >> new SecurityGroup(groupId: "123", ipPermissions: [])

    then:
    1 * existingSecurityGroup.addIngress([
      new IpPermission(ipProtocol: "tcp", fromPort: 111, toPort: 112, userIdGroupPairs: [
        new UserIdGroupPair(userId: prodCred.accountId, groupId: "id-bar")
      ])
    ])
    0 * _
  }

  void "existing permissions should not be re-created when a security group is modified"() {
    final existingSecurityGroup = Mock(SecurityGroupUpdater)
    def testCred = TestCredential.named("test")

    description.securityGroupIngress = [
      new SecurityGroupIngress(name: "bar", startPort: 111, endPort: 112, ipProtocol: "tcp"),
      new SecurityGroupIngress(name: "bar", startPort: 25, endPort: 25, ipProtocol: "tcp"),
      new SecurityGroupIngress(name: "bar", startPort: 80, endPort: 81, ipProtocol: "tcp")
    ]
    description.ipIngress = [
      new IpIngress(cidr: "10.0.0.1/32", startPort: 80, endPort: 81, ipProtocol: "tcp")
    ]

    when:
    op.operate([])

    then:
    3 * securityGroupLookup.getCredentialsForName("test") >> testCred
    3 * securityGroupLookup.getSecurityGroupByName("test", "bar", "vpc-123") >> Optional.of(new SecurityGroupUpdater(
      new SecurityGroup(groupId: "id-bar"),
      null
    ))

    then:
    1 * securityGroupLookup.getSecurityGroupByName("test", "foo", "vpc-123") >> Optional.of(existingSecurityGroup)
    1 * existingSecurityGroup.getSecurityGroup() >> new SecurityGroup(groupName: "foo", groupId: "123", ipPermissions: [
      new IpPermission(fromPort: 80, toPort: 81,
        userIdGroupPairs: [
          new UserIdGroupPair(userId: testCred.accountId, groupId: "grp"),
          new UserIdGroupPair(userId: testCred.accountId, groupId: "id-bar")
        ],
        ipRanges: ["10.0.0.1/32"], ipProtocol: "tcp"
      ),
      new IpPermission(fromPort: 25, toPort: 25,
        userIdGroupPairs: [new UserIdGroupPair(userId: testCred.accountId, groupId: "id-bar")], ipProtocol: "tcp"),
    ])

    then:
    1 * existingSecurityGroup.addIngress([
      new IpPermission(ipProtocol: "tcp", fromPort: 111, toPort: 112, userIdGroupPairs: [
        new UserIdGroupPair(userId: testCred.accountId, groupId: "id-bar")
      ])
    ])
    1 * existingSecurityGroup.removeIngress([
      new IpPermission(ipProtocol: "tcp", fromPort: 80, toPort: 81, userIdGroupPairs: [
        new UserIdGroupPair(userId: testCred.accountId, groupId: "grp")
      ])
    ])
    0 * _
  }

  void "should only append security group ingress"() {
    final existingSecurityGroup = Mock(SecurityGroupUpdater)
    def testCred = TestCredential.named("test")

    description.securityGroupIngress = [
      new SecurityGroupIngress(name: "bar", startPort: 111, endPort: 112, ipProtocol: "tcp"),
      new SecurityGroupIngress(name: "bar", startPort: 25, endPort: 25, ipProtocol: "tcp"),
      new SecurityGroupIngress(name: "bar", startPort: 80, endPort: 81, ipProtocol: "tcp")
    ]
    description.ingressAppendOnly = true

    when:
    op.operate([])

    then:
    3 * securityGroupLookup.getCredentialsForName("test") >> testCred
    3 * securityGroupLookup.getSecurityGroupByName("test", "bar", "vpc-123") >> Optional.of(new SecurityGroupUpdater(
      new SecurityGroup(groupId: "id-bar"),
      null
    ))

    then:
    1 * securityGroupLookup.getSecurityGroupByName("test", "foo", "vpc-123") >> Optional.of(existingSecurityGroup)
    1 * existingSecurityGroup.getSecurityGroup() >> new SecurityGroup(groupName: "foo", groupId: "123", ipPermissions: [
      new IpPermission(fromPort: 80, toPort: 81,
        userIdGroupPairs: [
          new UserIdGroupPair(userId: testCred.accountId, groupId: "grp"),
          new UserIdGroupPair(userId: testCred.accountId, groupId: "id-bar")
        ],
        ipRanges: ["10.0.0.1/32"], ipProtocol: "tcp"
      ),
      new IpPermission(fromPort: 25, toPort: 25,
        userIdGroupPairs: [new UserIdGroupPair(userId: testCred.accountId, groupId: "id-bar")], ipProtocol: "tcp"),
    ])

    then:
    1 * existingSecurityGroup.addIngress([
      new IpPermission(ipProtocol: "tcp", fromPort: 111, toPort: 112, userIdGroupPairs: [
        new UserIdGroupPair(userId: testCred.accountId, groupId: "id-bar")
      ])
    ])
    0 * _
  }

  void "should fail for missing ingress security group in vpc"() {
    def testCred = TestCredential.named("test")
    description.securityGroupIngress = [
      new SecurityGroupIngress(name: "bar", startPort: 111, endPort: 112, ipProtocol: "tcp")
    ]

    when:
    op.operate([])

    then:
    1 * securityGroupLookup.getCredentialsForName("test") >> testCred
    1 * securityGroupLookup.getSecurityGroupByName("test", "bar", "vpc-123") >> Optional.empty()
    0 * _

    then:
    IllegalStateException ex = thrown()
    ex.message == "The following security groups do not exist: 'bar' in 'test' vpc-123"
  }

  void "should add ingress by name for missing ingress security group in EC2 classic"() {
    def testCred = TestCredential.named("test")
    final existingSecurityGroup = Mock(SecurityGroupUpdater)
    description.securityGroupIngress = [
      new SecurityGroupIngress(name: "bar", startPort: 111, endPort: 112, ipProtocol: "tcp")
    ]
    description.vpcId = null

    when:
    op.operate([])

    then:
    1 * securityGroupLookup.getCredentialsForName("test") >> testCred
    1 * securityGroupLookup.getSecurityGroupByName("test", "bar", null) >> Optional.empty()

    then:
    1 * securityGroupLookup.getSecurityGroupByName("test", "foo", null) >> Optional.of(existingSecurityGroup)
    1 * existingSecurityGroup.getSecurityGroup() >> new SecurityGroup(groupName: "foo", groupId: "123", ipPermissions: [])
    0 * _

    then:
    1 * existingSecurityGroup.addIngress([
      new IpPermission(ipProtocol: "tcp", fromPort: 111, toPort: 112, userIdGroupPairs: [
        new UserIdGroupPair(userId: testCred.accountId, groupName: "bar")
      ])
    ])
  }

  void "should ignore name, peering status, vpcPeeringConnectionId when comparing ingress rules"() {
    def testCred = TestCredential.named("test")
    final existingSecurityGroup = Mock(SecurityGroupUpdater)
    final ingressSecurityGroup = Mock(SecurityGroupUpdater)
    description.securityGroupIngress = [
      new SecurityGroupIngress(name: "bar", startPort: 111, endPort: 112, vpcId: "vpc-123", ipProtocol: "tcp", accountName: "test")
    ]
    description.vpcId = "vpc-456"

    when:
    op.operate([])

    then:
    1 * securityGroupLookup.getCredentialsForName("test") >> testCred
    1 * securityGroupLookup.getSecurityGroupByName("test", "bar", "vpc-123") >> Optional.of(ingressSecurityGroup)

    then:
    1 * securityGroupLookup.getSecurityGroupByName("test", "foo", "vpc-456") >> Optional.of(existingSecurityGroup)
    1 * ingressSecurityGroup.getSecurityGroup() >> new SecurityGroup(groupName: "bar", groupId: "124", vpcId: "vpc-123")
    1 * existingSecurityGroup.getSecurityGroup() >> new SecurityGroup(groupName: "foo", groupId: "123", vpcId: "vpc-456", ipPermissions: [
      new IpPermission(ipProtocol: "tcp", fromPort: 111, toPort: 112, userIdGroupPairs: [
        new UserIdGroupPair(userId: testCred.accountId, groupName: "baz", groupId: "124", vpcId: "vpc-123", vpcPeeringConnectionId: "pca", peeringStatus: "active")])
    ])
    0 * _
  }

  void "userIdGroupPair comparison"() {
    expect:

    UpsertSecurityGroupAtomicOperation.pairsEqual(pairsA, pairsB) == expected

    where:
    accountA | accountB | vpcIdA    | vpcIdB | idA   | idB   | nameA       | nameB       | expected
    "1"      | "1"      | null      | null   | "sg1" | "sg1" | null        | null        | true
    "1"      | null     | null      | null   | "sg1" | "sg1" | null        | null        | false
    "1"      | "1"      | "vpc-foo" | null   | "sg1" | "sg1" | null        | null        | false
    "1"      | "1"      | null      | null   | "sg1" | "sg1" | "dontCareA" | "dontCareB" | true
    "1"      | "1"      | null      | null   | null  | "sg1" | null        | null        | false
    "1"      | "1"      | null      | null   | null  | "sg1" | "foo"       | "foo"       | true

    pairsA = [pair(idA, nameA, vpcIdA, accountA)]
    pairsB = [pair(idB, nameB, vpcIdB, accountB)]
  }

  void "userIdGroupPair comparison collection size fails fast"() {
    expect:

    UpsertSecurityGroupAtomicOperation.pairsEqual(pairsA, pairsB) == expected

    where:
    pairsA           | pairsB                 | expected
    [pair()]         | [pair()]               | true
    [pair()]         | null                   | false
    null             | null                   | true
    []               | null                   | true
    null             | []                     | true
    []               | []                     | true
    null             | [pair()]               | false
    [pair(), pair()] | [pair(), pair()]       | true
    [pair(), pair()] | [pair(), pair("asdf")] | false
  }

  UserIdGroupPair pair(String groupId = "sg-foo", String groupName = "foo", String vpcId = "vpc-foo", String userId = "account-foo") {
    new UserIdGroupPair(groupId: groupId, groupName: groupName, vpcId: vpcId, userId: userId)
  }

  void "ranges comparison"() {
    expect:
    UpsertSecurityGroupAtomicOperation.rangesEqual(rangesA, rangesB) == expected

    where:
    rangesA      | rangesB      | expected
    null         | null         | true
    []           | []           | true
    ["r1"]       | ["r1"]       | true
    ["r1", "r2"] | ["r2", "r1"] | true
    []           | null         | true
    null         | []           | true
    []           | ["r1"]       | false
    ["r1"]       | []           | false
    ["r1"]       | ["r2"]       | false
  }

}
