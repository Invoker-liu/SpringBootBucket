package com.xncoding.mongo.config;

import com.mongodb.ConnectionString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.boot.mongodb.autoconfigure.MongoProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 属性改名的验证测试：<b>Boot 3 的 {@code spring.data.mongodb.*} 在 Boot 4 里到底还行不行？</b>
 *
 * <h2>为什么非要专门写个测试来验这件事</h2>
 * 因为答案不符合直觉，而且看代码看不出来。
 * <p>
 * 静态检查（读 {@code spring-boot-mongodb.jar}）能得到的线索有三条：
 * <ol>
 *   <li>{@code additional-spring-configuration-metadata.json} 里那一族旧属性只有
 *       {@code name} 和 {@code deprecation}，<b>没有 {@code type}、没有 {@code sourceType}</b>，
 *       也就是纯粹给 IDE 看的提示；</li>
 *   <li>{@code MongoProperties} 的字段只对应 {@code spring.mongodb.*}；</li>
 *   <li>把 66 个 Boot jar 全扫一遍，含 {@code spring.data.mongodb} 字面量的 class
 *       一个都没有（除了管另外几个属性的 {@code DataMongoProperties} 和元数据文件本身）。</li>
 * </ol>
 * 三条线索都指向"旧名彻底不绑定"，但毕竟只是静态推断——
 * 有没有可能在别的地方（比如某个我没扫到的 jar）注册了别名？
 * <p>
 * 用 {@link ApplicationContextRunner} 跑一次真实上下文，这件事就没有争议了：
 * 只配旧属性，看框架最终往外抛的连接串是什么。
 * <p>
 * 这个类不连数据库：{@code MongoAutoConfiguration} 只会创建一个不发起连接的
 * {@code MongoClient}。所以这几个用例是毫秒级的，可以随便跑。
 *
 * <h2>踩过的坑：{@code determineUri()} 不是权威来源</h2>
 * 最初我用 {@code MongoProperties.determineUri()} 来断言"host/port 有没有绑上"，
 * 结果分字段配置那一条用例直接挂了：字段明明都绑上了，
 * 但 {@code determineUri()} 返回的是默认值 {@code mongodb://localhost/test}。
 * 反编译一看，这个方法一共就三行：
 * <pre>
 * return (uri != null) ? uri : "mongodb://localhost/test";
 * </pre>
 * 它<b>只认 {@code uri} 这一个字段</b>，host / port / database / username / password
 * 它一概不看。分字段那种写法是由另一个类消费的：
 * {@code PropertiesMongoConnectionDetails#getConnectionString()}，
 * 它会判断"配了 uri 就用 uri，否则拿 host/port 拼一个连接串"。
 * <p>
 * 所以下面所有断言一律走 {@link MongoConnectionDetails#getConnectionString()}——
 * 那才是客户端真正拿到的那个连接串。{@code determineUri()} 只在读 {@code uri} 一项时可用。
 */
class MongoPropertyRenameTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MongoAutoConfiguration.class));

    /**
     * 真正拿到本次上下文里的连接串。用 {@code ConnectionDetails} 而不是
     * {@code MongoProperties}，理由见类注释。
     */
    private ConnectionString connectionString(ApplicationContextRunner runner) {
        final ConnectionString[] holder = new ConnectionString[1];
        runner.run(context -> holder[0] = context.getBean(MongoConnectionDetails.class)
                .getConnectionString());
        return holder[0];
    }

    @Test
    @DisplayName("只写旧属性 spring.data.mongodb.uri：完全不生效，回退到默认的 mongodb://localhost/test")
    void legacyUriPropertyIsSilentlyIgnored() {
        ApplicationContextRunner r = runner.withPropertyValues(
                "spring.data.mongodb.uri=mongodb://127.0.0.1:29999/legacy");

        assertThat(connectionString(r).getHosts())
                .as("旧属性被完全忽略，连的仍是本机默认地址")
                // 注意：驱动会把"默认端口 27017"从 hosts 里省掉，
                // 所以这里拿到的是 "localhost" 而不是 "localhost:27017"。
                // 非默认端口（比如 27018）才会带上。
                .containsExactly("localhost");
        assertThat(connectionString(r).getDatabase())
                .as("库名也没绑上，退回默认的 test")
                .isEqualTo("test");
        assertThat(connectionString(r).getCredential())
                .as("没有任何鉴权信息")
                .isNull();
    }

    @Test
    @DisplayName("旧属性一族的其它成员同样不生效：host / port / database / username 都读不到")
    void legacyHostPropertiesAreIgnored() {
        ApplicationContextRunner r = runner.withPropertyValues(
                "spring.data.mongodb.host=192.168.1.97",
                "spring.data.mongodb.port=27018",
                "spring.data.mongodb.database=legacy_db",
                "spring.data.mongodb.username=someone",
                "spring.data.mongodb.password=secret");

        // 字段级：一个都没绑上
        r.run(context -> {
            MongoProperties properties = context.getBean(MongoProperties.class);
            assertThat(properties.getUri()).isNull();
            assertThat(properties.getHost()).as("host 没绑上").isNull();
            assertThat(properties.getPort()).as("port 没绑上").isNull();
            assertThat(properties.getDatabase()).as("database 没绑上").isNull();
            assertThat(properties.getUsername()).as("username 没绑上").isNull();
        });

        // 连接串级：框架最终决定连 localhost/test，而且是"悄悄"这么决定的
        ConnectionString cs = connectionString(r);
        assertThat(cs.getHosts()).containsExactly("localhost");
        assertThat(cs.getDatabase()).isEqualTo("test");
        assertThat(cs.getCredential()).isNull();
        // 一个配置项写错，行为是"悄悄用了本地默认库"，
        // 而不是"启动失败"或者"日志里报一条警告"。
        // 这就是改名这件事最难受的地方：错了没有任何提示，
        // 表现出来是"连不上库"或者"连上了但数据不见了"。
    }

    @Test
    @DisplayName("新属性 spring.mongodb.uri 正常绑定，作为对照")
    void newUriPropertyIsBound() {
        ApplicationContextRunner r = runner.withPropertyValues(
                "spring.mongodb.uri=mongodb://127.0.0.1:29999/legacy");

        assertThat(connectionString(r).getHosts()).containsExactly("127.0.0.1:29999");
        assertThat(connectionString(r).getDatabase()).isEqualTo("legacy");
    }

    @Test
    @DisplayName("新属性一族的其它成员正常绑定：host / port / database / username 全部生效")
    void newHostPropertiesAreBound() {
        ApplicationContextRunner r = runner.withPropertyValues(
                "spring.mongodb.host=192.168.1.97",
                "spring.mongodb.port=27018",
                "spring.mongodb.database=springboot4_mongo",
                "spring.mongodb.username=root",
                "spring.mongodb.password=root123456");

        r.run(context -> {
            MongoProperties properties = context.getBean(MongoProperties.class);
            assertThat(properties.getHost()).isEqualTo("192.168.1.97");
            assertThat(properties.getPort()).isEqualTo(27018);
            assertThat(properties.getDatabase()).isEqualTo("springboot4_mongo");
            assertThat(properties.getUsername()).isEqualTo("root");
        });

        // 分字段配置最终被拼成这样一个连接串 —— 这一步才能真正证明"配上了"
        ConnectionString cs = connectionString(r);
        assertThat(cs.getHosts()).containsExactly("192.168.1.97:27018");
        assertThat(cs.getDatabase()).isEqualTo("springboot4_mongo");
        assertThat(cs.getCredential()).isNotNull();
        assertThat(cs.getCredential().getUserName()).isEqualTo("root");
        assertThat(cs.getCredential().getSource())
                .as("没写 authSource 时，认证库默认跟着 database 走")
                .isEqualTo("springboot4_mongo");
    }

    @Test
    @DisplayName("uri 优先于 host/port：同时配了就用 uri 里的")
    void uriWinsOverHostAndPort() {
        ApplicationContextRunner r = runner.withPropertyValues(
                "spring.mongodb.uri=mongodb://127.0.0.1:29999/from_uri",
                "spring.mongodb.host=192.168.1.97",
                "spring.mongodb.port=27018");

        assertThat(connectionString(r).getHosts())
                .as("uri 一旦配了，host/port/database/username/password 全部忽略")
                .containsExactly("127.0.0.1:29999");
        assertThat(connectionString(r).getDatabase()).isEqualTo("from_uri");
    }

    @Test
    @DisplayName("单测 determineUri 的边界：它只认 uri 一项，分字段配置下它给的是默认值")
    void determineUriOnlyLooksAtUri() {
        // 这条用例是上面那次失败的化石。留着它，免得以后有人又想用
        // determineUri() 去判断"连接配置生效了没有"，然后再踩一遍。
        runner.withPropertyValues(
                        "spring.mongodb.host=192.168.1.97",
                        "spring.mongodb.port=27018")
                .run(context -> assertThat(context.getBean(MongoProperties.class).determineUri())
                        .as("host/port 明明配了，但 determineUri() 视而不见")
                        .isEqualTo(MongoProperties.DEFAULT_URI)
                        .isEqualTo("mongodb://localhost/test"));
    }
}
