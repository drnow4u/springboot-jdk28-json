package com.github.drnow4u.jdk28json;

import jdk.incubator.json.Json;
import jdk.incubator.json.JsonValue;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.PayloadTooLargeException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonRecordHttpMessageConverterTests {

    record Address(String city, int zip) {
    }

    record Person(String name, int age, long id, double score, boolean active, Integer rank, Address address) {
    }

    record Counter(int count) {
    }

    record Tags(List<String> tags) {
    }

    private final JsonRecordHttpMessageConverter converter = new JsonRecordHttpMessageConverter();

    @Test
    void supportsOnlyRecordsAsJson() {
        assertThat(converter.canRead(Person.class, MediaType.APPLICATION_JSON)).isTrue();
        assertThat(converter.canWrite(Person.class, MediaType.APPLICATION_JSON)).isTrue();
        assertThat(converter.canRead(String.class, MediaType.APPLICATION_JSON)).isFalse();
        assertThat(converter.canRead(Person.class, MediaType.TEXT_PLAIN)).isFalse();
    }

    @Test
    void readsAllSupportedTypesAndNestedRecords() throws IOException {
        Person person = read(Person.class, """
                {"name":"Ada","age":36,"id":9007199254740993,"score":9.5,"active":true,"rank":1,
                 "address":{"city":"London","zip":12345}}""");

        assertThat(person).isEqualTo(new Person("Ada", 36, 9007199254740993L, 9.5, true, 1, new Address("London", 12345)));
    }

    @Test
    void readsMissingAndNullFieldsAsNull() throws IOException {
        record Optional(String name, Integer rank, Address address) {
        }

        assertThat(read(Optional.class, "{\"rank\":null}")).isEqualTo(new Optional(null, null, null));
    }

    @Test
    void ignoresUnknownFields() throws IOException {
        assertThat(read(Counter.class, "{\"count\":3,\"extra\":\"ignored\"}")).isEqualTo(new Counter(3));
    }

    @Test
    void rejectsMissingPrimitiveField() {
        assertThatThrownBy(() -> read(Counter.class, "{}"))
                .isInstanceOf(HttpMessageNotReadableException.class)
                .hasMessageContaining("missing value for 'count'");
    }

    @Test
    void rejectsWrongValueType() {
        assertThatThrownBy(() -> read(Counter.class, "{\"count\":\"three\"}"))
                .isInstanceOf(HttpMessageNotReadableException.class);
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> read(Counter.class, "{\"count\":"))
                .isInstanceOf(HttpMessageNotReadableException.class);
    }

    @Test
    void rejectsTopLevelValueThatIsNotAnObject() {
        assertThatThrownBy(() -> read(Counter.class, "[1,2,3]"))
                .isInstanceOf(HttpMessageNotReadableException.class)
                .hasMessageContaining("expected a JSON object");
    }

    @Test
    void rejectsUnsupportedComponentTypeOnRead() {
        assertThatThrownBy(() -> read(Tags.class, "{\"tags\":[\"a\"]}"))
                .isInstanceOf(HttpMessageNotReadableException.class)
                .hasMessageContaining("unsupported type java.util.List");
    }

    @Test
    void rejectsUnsupportedComponentTypeOnWrite() {
        assertThatThrownBy(() -> write(new Tags(List.of("a"))))
                .isInstanceOf(HttpMessageNotWritableException.class);
    }

    @Test
    void rejectsBodyLargerThanLimit() {
        var limited = new JsonRecordHttpMessageConverter(DataSize.ofBytes(16));
        var input = new MockHttpInputMessage("{\"count\":1234567890}".getBytes(StandardCharsets.UTF_8));
        input.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        assertThatThrownBy(() -> limited.read(Counter.class, input))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void rejectsDeclaredContentLengthLargerThanLimitWithoutReadingBody() {
        var limited = new JsonRecordHttpMessageConverter(DataSize.ofBytes(16));
        var input = new MockHttpInputMessage("{}".getBytes(StandardCharsets.UTF_8));
        input.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        input.getHeaders().setContentLength(1_000_000);

        assertThatThrownBy(() -> limited.read(Counter.class, input))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void acceptsBodyExactlyAtLimit() throws IOException {
        String json = "{\"count\":1}";
        var limited = new JsonRecordHttpMessageConverter(DataSize.ofBytes(json.length()));
        var input = new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8));
        input.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        assertThat(limited.read(Counter.class, input)).isEqualTo(new Counter(1));
    }

    @Test
    void abbreviatesErrorMessageThatRepeatsHugeInput() {
        String hugeNumber = "1".repeat(100_000);

        assertThatThrownBy(() -> read(Counter.class, "{\"count\":" + hugeNumber + "}"))
                .isInstanceOf(HttpMessageNotReadableException.class)
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(400).contains("chars)"));
    }

    @Test
    void writesRecordAsJsonObject() throws IOException {
        JsonValue json = Json.parse(write(new Person("Ada", 36, 9007199254740993L, 9.5, true, null, new Address("London", 12345))));

        assertThat(json.get("name").asString()).isEqualTo("Ada");
        assertThat(json.get("age").asInt()).isEqualTo(36);
        assertThat(json.get("id").asLong()).isEqualTo(9007199254740993L);
        assertThat(json.get("score").asDouble()).isEqualTo(9.5);
        assertThat(json.get("active").asBoolean()).isTrue();
        assertThat(json.get("rank").tryValue()).isEmpty();
        assertThat(json.get("address").get("city").asString()).isEqualTo("London");
        assertThat(json.get("address").get("zip").asInt()).isEqualTo(12345);
    }

    @Test
    void writesPlainJsonContentType() throws IOException {
        var output = new MockHttpOutputMessage();
        converter.write(new Counter(1), MediaType.APPLICATION_JSON, output);

        assertThat(output.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    void roundTripsRecord() throws IOException {
        var person = new Person("Zoë", 1, 2L, -0.25, false, 7, new Address("Kraków", 30001));

        assertThat(read(Person.class, write(person))).isEqualTo(person);
    }

    private <T extends Record> T read(Class<T> type, String json) throws IOException {
        var input = new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8));
        input.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return type.cast(converter.read(type, input));
    }

    private String write(Record record) throws IOException {
        var output = new MockHttpOutputMessage();
        converter.write(record, MediaType.APPLICATION_JSON, output);
        return output.getBodyAsString(StandardCharsets.UTF_8);
    }
}
