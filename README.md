# commons

Thư viện Java dùng chung cho các service MSM (package `com.msm.core`). Cung cấp:

- **Typesafe metadata**: khai báo cột/field có kiểu (`TypedAttribute`, `MetaFieldBuilder`, `DataRecord`).
- **Dynamic query** trên jOOQ: filter / lookup / insert / update / delete dựa theo metadata, có phân quyền theo data scope.
- **Action & hook engine**: định tuyến request theo `resource` + `action` tới handler, kèm hook before / after / after-commit.
- **Metadata object** build từ JPA metamodel và annotation.
- **Validation**, **security** (permission, data scope), **exception** chuẩn và các tiện ích (`Utils`).

> Thư viện **không phụ thuộc Spring** và không có auto-configuration. Service sử dụng phải tự tạo bean và đăng ký các hook tĩnh (xem [Khởi tạo](#khởi-tạo)). Với service Spring Boot, nên dùng [generic-object-management](https://github.com/lib4jx/generic-object-management): thư viện đó đã làm sẵn phần này.

## Cài đặt

Bản phát hành là **git tag** (ví dụ `2.3.7`), build qua [JitPack](https://jitpack.io/#lib4jx/commons). Dòng `version` trong `build.gradle.kts` không phải version được publish.

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.lib4jx:commons:2.3.7")
}
```

Yêu cầu: **Java 21**, **PostgreSQL** (cần extension `unaccent` cho toán tử `LIKE`/`CONTAINS`).

Thư viện khai báo các dependency dạng `implementation`. Nếu code của service dùng trực tiếp type của jOOQ (3.21.x), Jackson, QueryDSL (OpenFeign 6.10.x) hay Hibernate 6.6 thì phải tự khai báo các dependency đó, đúng version.

## Cấu trúc package

| Package | Nội dung |
|---|---|
| `action` | Action/hook engine: `@Handler`, `@CreateHandler`, `@HookBefore`…, `ActionContext`, `DefaultActionExecutor`, `DefaultHookEngine`, `TransactionUtils` |
| `commons` | Tiện ích `Utils.*`, `Constants`, `GenericTypeResolverFactory` (chuỗi kiểu → Jackson `JavaType`) |
| `dynamicquery` | CRUD jOOQ theo metadata: `DefaultFilterQuery`, `DefaultDynamicInsert/Update/Delete`, operator, `FieldResolver`, `ObjectMetadataFactory`, `JavaTypeMappingFactory` |
| `exceptions` | `GenericBaseException`, factory `CommonErrors`, `ErrorCodeEnum` |
| `filter` | DTO request/response dùng chung (`ObjectFilterRequest`, `PageResponse`…) và engine filter QueryDSL/JPA (`AdvancedFilterService`) |
| `metadata` | `ObjectMetadata`, `Attribute`, annotation (`@AttributeDefinition`, `@ObjectDefinitionRef`…), `typesafe` (`TypedAttribute`, `MetaFieldBuilder`, `DataRecord`), `mapper` (`RecordMapper`, `References`) |
| `security` | Data scope (OWNER, TEAM, BU, PARENT_CHILD, ORG), `RequestContextHolder`, `@SecuredField`, `@IgnorePermission` |
| `strategy` | `AbstractStrategyRegistry`: registry strategy theo key |
| `validate` | Validate attribute (required, kiểu, min/max/regex), rule MVEL |

## Khởi tạo

Thực hiện một lần khi ứng dụng khởi động:

```java
// 1. Build metadata từ JPA entity và đăng ký vào ObjectMetadataFactory
ObjectMetadataBuilder.buildFromMetamodel(entityManager.getMetamodel());

// 2. Cung cấp người dùng hiện tại (thường đọc từ ThreadLocal / SecurityContext)
RequestContextHolder.setRequestContextProvider(() -> currentRequestContext());

// 3. Nối hook after-commit với transaction manager
TransactionUtils.setHook(myTransactionHook);

// 4. Đăng ký package chứa kiểu tự định nghĩa dùng trong chuỗi kiểu
GenericTypeResolverFactory.registerPackage("com.mycompany.domain.*");

// 5. Tạo các bean query
var filterQuery = new DefaultFilterQuery(dsl, new SecurityConditionProvider(new DataScopeConditionResolver()));
var insert = new DefaultDynamicInsert(dsl);
var update = new DefaultDynamicUpdate(dsl);
var delete = new DefaultDynamicDelete(dsl, update);
```

Những điểm cần nhớ:

- Nếu không set `RequestContextProvider`, mặc định là user SYSTEM **không có quyền**, nên mọi query có security đều trả về **rỗng**.
- Nếu không set `TransactionUtils.setHook`, hook "after commit" chạy **ngay lập tức**, trước khi transaction commit.
- Thư viện **không tự scan** handler/hook. Service phải tự đăng ký vào `ActionHandlerFactory` và `HookDefinitionHandlerFactory`.
- Hàm `unaccent` cho Hibernate (`CustomUnaccent`) phải được đăng ký qua `META-INF/services/org.hibernate.boot.model.FunctionContributor`.
- Các registry tĩnh (operator, data type, rule, handler) **không thread-safe**, nên chỉ đăng ký lúc khởi động.

## Typesafe metadata

Mỗi bảng có một class `*Meta` khai báo `TABLE` và các `TypedAttribute`:

```java
import static com.msm.core.commons.GenericTypeResolverFactory.resolve;
import static com.msm.core.metadata.typesafe.MetaFieldBuilder.attr;

public final class OrderMeta {
    private OrderMeta() {}

    public static final String OBJECT_NAME = "order";
    public static final Table<?> TABLE = DSL.table(DSL.name("order"));

    public static final TypedAttribute<UUID> ID =
            attr(TABLE, "id", "id", UUID.class);
    public static final TypedAttribute<String> ORDER_CODE =
            attr(TABLE, "orderCode", "order_code", String.class);
    public static final TypedAttribute<Map<String, Object>> CUSTOM_VALUES =
            attr(TABLE, "customValues", "custom_values", resolve(Map.class, String.class, Object.class));
    // Field không gắn cột (DTO, nội dung JSON, reference)
    public static final TypedAttribute<Map<String, Object>> SHIP_TO_ACCOUNT_SITE =
            attr("shipToAccountSite", resolve(Map.class, String.class, Object.class));
}
```

### Chọn overload `MetaFieldBuilder`

| Trường hợp | Cách viết |
|---|---|
| Cột kiểu đơn giản | `attr(TABLE, "camelField", "snake_column", Type.class)` |
| Cột cần kiểu jOOQ riêng (array, UUID[], converter) | `attr(TABLE, field, column, DataType<T>)` |
| Cột JSONB kiểu generic (`Map`, `List<Foo>`) | `attr(TABLE, field, column, resolve(...))` |
| Field không gắn cột (DTO, nội dung JSON) | `attr("field", Type.class)` / `attr("field", JavaType)` |
| Giá trị nằm trong JSONB `custom_values` | `attrRef(TABLE, refName, Type.class)` / `attrRef(TABLE, refName, JavaType)` |

Field không gắn cột có `getField() == null`, vì vậy **không được** dùng nó trong câu SQL.

Kiểu generic có thể tạo bằng `resolve(List.class, UUID.class)`, `resolve(Map.class, String.class, Object.class)` hoặc từ chuỗi `resolve("List<UUID>")`. Chuỗi kiểu mặc định chỉ nhận các package `java.lang`, `java.util`, `java.math`, `java.text`, `java.time`. Với kiểu khác, dùng FQCN hoặc gọi `registerPackage`.

### Quy ước đặt tên

- **Field** (key trong payload/map) viết `camelCase`; **cột** DB viết `snake_case`.
- **Tên object** (`OBJECT_NAME`, `resource`) viết thường, liền nhau (`importjob`, `v2blanketorder`). `ObjectMetadataFactory` phân biệt hoa thường.
- Các field có tên cố định:

  | Ý nghĩa | Field |
  |---|---|
  | Khóa chính | `id` |
  | Khóa lạc quan | `version` |
  | Cờ xóa mềm | `isDeleted` |
  | Audit | `createdAt/By/ById`, `updatedAt/By/ById`, `deletedAt/By/ById` |
  | Team (data scope) | `teamId` |

- **Thuộc tính động** lưu trong cột JSONB `custom_values`, tương ứng với field `customValues`.
- **Reference** của field `x` lưu trong `xReference` (JSON chỉ chứa các field khai báo, mặc định là `id`, `code`, `name`). Nên tạo reference bằng `References` hoặc `RecordMapper` (khai báo `static final`).

### `DataRecord`

```java
DataRecord record = DataRecord.copyOf(map);      // copy, không ảnh hưởng map gốc
String code = record.get(OrderMeta.ORDER_CODE);
record.set(OrderMeta.ORDER_CODE, "SO001");
```

`DataRecord.of(map)` **không copy**: ghi vào record sẽ sửa luôn map gốc, và lỗi nếu map gốc immutable (`Map.of`).

### Build metadata từ JPA

- **Tên object**: entity name viết thường.
- **Tên bảng**: lấy từ `@Table(name)`.
- **Tên cột**: lấy theo thứ tự `@AttributeDefinition.columnName`, rồi `@Column(name)`, rồi đến **tên field Java**. Vì vậy luôn khai báo `@Column(name = "snake_case")`.
- **Required**: `@AttributeDefinition(required = ...)` được ưu tiên trước `@Column(nullable = false)` và `@NotNull`.
- **Annotation thường dùng**:

  | Annotation | Dùng để |
  |---|---|
  | `@AttributeDefinition(..., attributeRef = @AttributeDefinitionRef(...))` | Cấu hình attribute và reference |
  | `@ObjectDefinitionRef(relationType = CHILD_OF)` | Object con kế thừa quyền của object cha |
  | `@IgnorePermission` | Tắt security cho object |
  | `@SecuredField(TEAM)` | Đánh dấu cột dùng cho data scope |
  | `@AttributeDefinitionRef(..., reverseName = "products")` | Đặt tên nghiệp vụ cho relation one-to-many ở object được tham chiếu (không khai thì tên tự sinh, xem [Relation](#relation-cùng-service)) |
  | `@AttributeDefinition(filterable = false)` | Field nhạy cảm: không lọc, không chọn, không sort được qua relation |

## Dynamic query

### Request filter

```json
{
  "returnFields": ["id", "orderCode", "status"],
  "search": { "keyword": "abc", "fields": ["orderCode", "name"] },
  "filters": {
    "operator": "AND",
    "conditions": [
      { "field": "status", "operator": "IN", "value": ["NEW", "APPROVED"] },
      { "field": "createdAt", "operator": "BETWEEN", "value": ["2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"] },
      { "operator": "OR", "conditions": [ { "field": "teamId", "operator": "EQUALS", "value": null } ] }
    ]
  },
  "pageRequest": { "page": 1, "size": 20, "sorts": [ { "attribute": "createdAt", "direction": "DESC" } ] }
}
```

- `page` **bắt đầu từ 1**. Tổng số bản ghi chỉ được tính khi có `pageRequest`.
- Chỉ filter, sort hoặc select được các field **có trong metadata**. Field lạ ném `FieldNotFoundException`.
- Field JSON dùng được đường dẫn `a.b.c`. Giá trị lấy ra là **text**, nên `GREATER_THAN`/`LESS_THAN` so sánh theo chuỗi; chỉ `BETWEEN` có ép kiểu.
- `filter`, `lookup`, `filterStream` và `findByCondition` của `DefaultFilterQuery` đều áp điều kiện data scope của user. Cần lấy dữ liệu không lọc quyền (job chạy nền…) thì dùng `InternalQueryService`.
- `filter`, `lookup` và `filterStream` tự động:
  - thêm `(isDeleted = false OR isDeleted IS NULL)` nếu object có `isDeleted`:
    - `DefaultFilterQuery`: **luôn** thêm, kể cả khi request có điều kiện riêng trên `isDeleted`, nên không đọc được bản ghi đã xóa mềm;
    - `InternalFilterQuery`: chỉ thêm khi request chưa filter field này (giữ như cũ);
  - thêm điều kiện data scope của user (chỉ `DefaultFilterQuery`).

### Relation (cùng service)

Filter của một object có thể dùng object khác **cùng service** (cùng database) qua các `attributeRef` object đã có. Object của service khác thì không: dùng cột `<ref>_code` / JSON `<ref>IdReference` đã lưu sẵn.

> **Không cần cấu hình relation.** Field nào đã có `attributeRef` trỏ tới object cùng service thì dùng được ngay `@<reference>.<field>` (`@blanketOrderIdReference.code`), không cần annotation, bảng hay file cấu hình nào khác, và client không phải khai báo gì trước khi gửi request.
> - Field chưa có `attributeRef` thì thêm `attributeRef` cho field đó (xem [Build metadata từ JPA](#build-metadata-từ-jpa)); đó là thay đổi duy nhất.
> - `reverseName` là tùy chọn, chỉ để đặt tên dễ đọc cho chiều one-to-many; không có thì tên tự sinh (bảng bên dưới).
> - Node `{ "relation", "match", "filters" }` trong `filters` cũng không phải cấu hình: đó là cách viết điều kiện trong request, chỉ cần khi muốn `NONE` (`NOT EXISTS`) hoặc gom nhiều điều kiện vào cùng một dòng con. Điều kiện thường thì viết `{"field": "@<reference>.<field>", …}`.

```json
{
  "returnFields": ["*", "productIdReference.code", "@blanketOrderIdReference.code", "@blanketOrderIdReference.quoteIdReference.code"],
  "search": { "keyword": "bo.26", "fields": ["productIdReference.code", "@blanketOrderIdReference.code", "@blanketOrderIdReference.name"] },
  "filters": {
    "operator": "AND",
    "conditions": [
      { "relation": "blanketOrderIdReference", "match": "ANY",
        "filters": { "operator": "AND", "conditions": [ { "field": "status", "operator": "EQUALS", "value": "ACTIVE" } ] } },
      { "field": "@blanketOrderIdReference.code", "operator": "IN", "value": ["BO.2611.00012"] }
    ]
  },
  "pageRequest": { "page": 1, "size": 20, "sorts": [ { "attribute": "@blanketOrderIdReference.code", "direction": "ASC" } ] }
}
```

- **Relation của một object:** chỉ sinh từ `attributeRef` trỏ tới object **của service này**:

  | Loại | Có từ đâu | Tên |
  |---|---|---|
  | many-to-one | Attribute của object có `attributeRef` | **Tên reference field** (`fieldName` của `attributeRef`), đúng chuỗi client dùng để đọc JSON reference (`blanketOrderIdReference.code`): `blanketOrderIdReference`, `creditAnalystReference`. Tên ngắn của 2.4.5 vẫn nhận làm alias: reference bỏ `IdReference` (`blanketOrder`), không có thì tên field bỏ `Id` |
  | one-to-many | `attributeRef` ở object khác (object con) trỏ về object này | `reverseName` nếu có. Không có thì lấy tên object con (`v2blanketorderproduct`). Object con trỏ về cùng object này nhiều lần mà không có `reverseName` thì lấy `<object con>_<field khóa>` (`contract_customerId`) |

  Ví dụ: `attributeRef` có sẵn của dòng sản phẩm đơn khung đã cho `@blanketOrderIdReference` trên dòng sản phẩm; `reverseName` (tùy chọn) đổi tên chiều ngược trên đơn khung thành `@products` (thay cho `@v2blanketorderproduct`):

  ```java
  @AttributeDefinition(attributeRef = @AttributeDefinitionRef(
          fieldName = "blanketOrderIdReference", objectRef = "v2blanketorder", reverseName = "products"))
  @Column(name = "blanket_order_id")
  private UUID blanketOrderId;
  ```

  Cột là list (multi-select) thì không sinh relation.

  Tên không khai báo thì báo `FieldValueInvalidException` ("Unknown relation"). Hai relation trùng tên thì báo "Ambiguous relation".
- **Cách viết đường dẫn tới field của object cha** (từ 2.4.6):

  | Đường dẫn | Đọc từ |
  |---|---|
  | `blanketOrderIdReference.code` | JSON reference lưu trên dòng đang query: giá trị lúc lưu, chỉ các field có trong JSON, không cần subquery |
  | `@blanketOrderIdReference.code` | dòng object cha hiện tại: field nào cũng được (cột hay JSON), dòng cha đã xóa hoặc ngoài quyền đọc ra `null` |
  | `@blanketOrderIdReference.salePersonIdReference.name` | dòng đơn khung hiện tại, rồi JSON `salePersonIdReference` lưu trên đơn khung đó |

  Hai đường dẫn của cùng một reference chỉ khác nhau dấu `@`: không `@` là bản đã lưu, có `@` là dữ liệu hiện tại của object cha (chỉ object cùng service).
  `@` chỉ áp cho đoạn đầu (tên relation, cắt ở dấu chấm đầu tiên); phần sau là field path trên object cha, phân giải như field thường.

  Đường dẫn `<x>IdReference.<field>` (không `@`):
  - dùng được ở `returnFields`, `sorts`, `search.fields`, `filters` và bên trong relation (`@<relation>.<x>IdReference.<field>`, field trong node `relation`);
  - object phải khai báo reference đó (`attributeRef` với `fieldName = "<x>IdReference"`); `filters` và `search` báo "not found" với khóa JSON không khai báo, nên request không dò được khóa tùy ý trong `custom_values`;
  - key trong kết quả đúng bằng chuỗi đã gửi (`row["blanketOrderIdReference.code"]`).

  Hai reference cùng trỏ một object (`customerIdReference`, `invoiceAccountIdReference` → `account`) là hai relation khác tên. Tên đầy đủ và alias của cùng một relation chỉ tính **một** relation trong giới hạn của query.
- **Node `{ "relation", "match", "filters" }`** trong `filters` thành `EXISTS` (`match: ANY`, mặc định) hoặc `NOT EXISTS` (`match: NONE`) của subquery trên object đích, với alias `rel_<n>`. Dòng của object được query **không bị nhân lên**, nên phân trang và tổng số vẫn đúng. `filters` rỗng nghĩa là "có (hoặc không có) dòng liên quan".
- **`@<relation>.<field>`** trong `returnFields` và `sorts` là scalar subquery, **chỉ** qua relation many-to-one. Key trong kết quả đúng bằng chuỗi đó. Dòng liên quan không có, đã xóa hoặc ngoài quyền thì đọc ra `null`.
- **`returnFields`:**
  - `null` hoặc `[]`: mọi field của object đang query, như cũ (không có field relation);
  - có `"*"`: `"*"` là mọi field của object đó, thêm được field relation, ví dụ `["*", "@blanketOrderIdReference.code"]`. Field trùng chỉ lấy một lần;
  - liệt kê từng field: lấy đúng các field đó.
- **`{"field": "@<relation>.<field>", "operator", "value"}`** trong `conditions` giống một node `relation` (ANY) chứa điều kiện đó. Mỗi điều kiện như vậy là một `EXISTS` riêng: qua relation one-to-many, hai điều kiện có thể khớp hai dòng con khác nhau. Muốn cùng một dòng con thì gom vào một node `relation`.
- **`@<relation>.<field>`** trong `search.fields`: các field của cùng một relation gom vào **một** `EXISTS`, OR với các field của chính object.
- **Quyền** (chỉ `DefaultFilterQuery`):
  - user phải có quyền VIEW trên object đích (object cha nếu đích là `CHILD_OF`), không thì ném `RelationAccessDeniedException` (mã `RELATION_ACCESS_DENIED`);
  - subquery luôn có data scope VIEW và điều kiện chưa xóa mềm của object đích.

  `InternalFilterQuery` không áp quyền hay data scope, nhưng vẫn bỏ dòng đích đã xóa mềm.
- **Giới hạn** (`RelationSettings`, đổi được lúc khởi động):
  - mỗi query tối đa **3** relation khác nhau;
  - **1** cấp (filter của relation không chứa relation khác);
  - query có relation có timeout **30 s**.
- **Field `filterable = false`** của object đích không dùng được qua relation. Lọc trực tiếp trên object đó vẫn được.
- **Hàm cũ không hỗ trợ relation:** `FilterBuilder.build(filter, meta)`, `SelectBuilder.buildFields(meta, fields)` và `SortingApplier.apply(query, request, meta)` từ chối relation. Bản có `RelationQueryContext` thì hỗ trợ.

### Toán tử

| Toán tử | Ghi chú |
|---|---|
| `EQUALS`, `NOT_EQUALS` | `EQUALS` với `null` thành `IS NULL` |
| `LIKE` | `unaccent(lower(field)) LIKE unaccent('%keyword%')`. `%` và `_` trong từ khóa **không** được escape. Cột không phải chuỗi bị bỏ qua |
| `GREATER_THAN(_OR_EQUAL)`, `LESS_THAN(_OR_EQUAL)` | |
| `IN`, `NOT_IN` | `value` phải là mảng |
| `BETWEEN` | `value` là mảng đúng 2 phần tử |
| `CONTAINS` | Giá trị đơn: LIKE không dấu. Mảng: `@>` trên cột array |
| `CONTAINS_ONE_OF` | `&&` trên cột array |
| `NOT_CONTAINS`, `NOT_CONTAINS_ONE_OF` | **Chưa hỗ trợ** (ném `UnsupportedException`) |

### Ghi dữ liệu

- `update*` và `delete*` **bắt buộc có điều kiện where**.
- **Khóa lạc quan** (object có field `version`):
  - **Update theo id** (`updateById*`, `batchUpdate`, `update(List)`, `updateReturning(List)`): payload **phải có `version` dạng số**. Thư viện ghi `version + 1` với điều kiện `WHERE version = <cũ>`. Không có dòng nào bị ảnh hưởng thì ném `OptimisticLockingFailureException`.
  - **Update theo điều kiện** (`update`, `updateReturning`, `updateWithExpressions` với `Condition`, kể cả xóa mềm theo điều kiện): **không** kiểm tra version, `version` trong payload bị bỏ qua, mỗi dòng khớp được `version = version + 1` của chính nó.
- **Không có dòng nào được update:** ném `OptimisticLockingFailureException` nếu là update theo id trên object có `version`; các trường hợp khác ném `ObjectNotFoundException`.
- **Khóa chính** không bao giờ bị update: `id` trong payload bị bỏ qua (update theo id dùng nó để tìm dòng). Item của batch update thiếu `id` thì ném `FieldValueRequiredException` trước khi chạy SQL.
- **Batch update** (`batchUpdate`, `update(List)`, `updateReturning(List)`): chạy hết mọi dòng (mọi lô 1000), rồi ném **một** lỗi liệt kê tất cả dòng không được update (tối đa 20 chỉ số, tính trên toàn danh sách). Lúc đó các dòng khác **đã được ghi**: gọi trong transaction nếu cần "tất cả hoặc không".
- **`updateReturning(meta, values, where)`** chỉ dành cho **đúng một dòng**: không khớp thì `ObjectNotFoundException`; khớp nhiều dòng thì `FieldValueInvalidException`, ném **sau khi** các dòng đã được update (cần transaction để rollback). Muốn update nhiều dòng và lấy kết quả thì dùng `updateAllReturning`.
- **Payload không có gì để ghi** (mọi key đều lạ, hoặc chỉ có `id`): ném `FieldValueRequiredException`, message liệt kê các key lạ.
- **Xóa:** `delete*` là **xóa mềm** (thực chất là update), nên bạn phải tự truyền `isDeleted`, `deletedAt`… Muốn xóa thật thì dùng `forceDelete*`.
- **Insert:** bỏ qua các giá trị `null` (cột lấy DEFAULT của DB). Insert/update hàng loạt được chia lô 1000 bản ghi.
- **Insert/upsert nhiều dòng:** các dòng được phép có tập field khác nhau. Thư viện gom các dòng có cùng tập cột và chạy một câu lệnh cho mỗi nhóm; nếu mọi dòng cùng tập cột thì vẫn chỉ một câu. Field mà một dòng không có sẽ không xuất hiện trong câu của dòng đó: insert thì lấy DEFAULT, upsert thì giữ giá trị cũ. Dòng không có gì để ghi (mọi key đều lạ, hoặc insert mà mọi field đều `null`) thì ném `FieldValueRequiredException` trước khi chạy SQL, message liệt kê các key lạ; áp dụng cho mọi hàm insert/upsert, kể cả `upsertBatch` (trước đây bỏ qua dòng đó).
- **Upsert:** không bao giờ cập nhật khóa chính `id` và `createdAt/By/ById` của bản ghi đã có. Field có giá trị `null` trong payload **được ghi** (xóa giá trị cũ, giống `update`); field không có trong payload thì giữ giá trị cũ. Với bản ghi mới, `null` được insert nguyên như vậy (không lấy DEFAULT).
- **Upsert và cột NOT NULL:** PostgreSQL kiểm tra NOT NULL trên dòng dự định insert **trước khi** xét conflict, nên payload upsert phải có đủ các cột NOT NULL không có DEFAULT, kể cả khi bản ghi đã tồn tại.
- **Upsert theo cột** (`upsert(meta, values, conflictFieldNames, condition)`): `conflictFieldNames` là tên field (hoặc tên cột); field lạ ném `FieldNotFoundException`. `condition` là predicate của **partial unique index** (`ON CONFLICT (...) WHERE ...`), phải khớp index đó; nó không lọc bản ghi được update.
- **Payload:** key không có trong metadata bị **bỏ qua mà không báo lỗi**.
- **Không tự động:** thao tác ghi **không** kiểm tra data scope (tự gọi `SecurityCheckProvider.checkDataScope`) và **không** tự điền field audit.

## Action & hook

```java
@Handler(resource = "order", action = "approve")
public Map<String, Object> approve(ActionContext<Map<String, Object>> ctx) { ... }

@HookBefore(resource = "order", action = Constants.Action.CREATE, order = 10)
public void beforeCreate(ActionContext<Map<String, Object>> ctx) { ... }
```

- Key định tuyến (viết thường):

  | Loại | Key |
  |---|---|
  | Handler | `handler:<resource>:<action>` |
  | Hook | `hook:<resource>:<action>:<PHASE>` |

- Không tìm thấy handler riêng thì dùng handler `resource = "*"`. Có nhiều handler cùng khớp thì ném `IllegalStateException`.
- Hook riêng của một resource **thay thế** hook `*`, không gộp chung.
- Engine **không sắp xếp theo `order`**, nên phải đăng ký đúng thứ tự.
- Hook after-commit chạy trên thread khác, nên **không** có `RequestContext` (ThreadLocal). Lỗi trong hook này chỉ được ghi log.

## Exception

- **Tạo lỗi:** dùng factory `CommonErrors` (`required`, `invalid`, `objectNotFound`, `fieldNotFoundException`, `optimisticLockingFailureException`…). Các exception đều kế thừa `GenericBaseException` (unchecked), mang `ErrorCode` và map `params`.
- **Stack trace:** exception **không có stack trace** (đã override `fillInStackTrace`). Khi bọc lỗi khác, nhớ log kèm cause.
- **Mã lỗi riêng:** implement interface `ErrorCode`, đặt tên dạng `UPPER_SNAKE_CASE`.
- **i18n:** chưa có. Message validate là tiếng Việt viết cứng trong `Constants`.

## Tiện ích

- **Cách dùng:** gọi qua singleton `Utils.STR`, `Utils.O`, `Utils.CL`, `Utils.N`, `Utils.DATES`… Method là instance method, không phải static.
- **`Utils.O`:** bọc một Jackson `ObjectMapper` dùng chung (bỏ qua property lạ, ISO date, chuỗi rỗng thành `null`). `convertToType(...)` chuyển đổi theo `Class`, `JavaType`, `TypeReference` hoặc chuỗi kiểu.
- **`DateUtils`:** mặc định UTC. `NumberUtils.format` dùng dấu `.` ngăn cách hàng nghìn và `,` cho phần thập phân.
- **Deprecated:** `RefDataResolver` và `@RefProfileConfig`. Dùng `References` / `RecordMapper` thay thế.

## Build & phát hành

```bash
./gradlew compileJava                       # build nhanh
./gradlew clean publishToMavenLocal -x test # giống lệnh JitPack chạy
```

1. Merge thay đổi vào `main`.
2. Tạo GitHub Release với tag mới trên `main` (semver, không có tiền tố `v`, ví dụ `2.3.8`).
3. Mở <https://jitpack.io/#lib4jx/commons> và bấm **Get it** ở tag mới để build.
4. Cập nhật version ở các service dùng thư viện (ví dụ `msmCommonVersion` trong generic-object-management).

`jitpack.yml` cố định JDK `21.0.2-open` qua SDKMAN, vì alias `openjdk21` của JitPack có thể trỏ tới bản không tải được.

## Hạn chế đã biết

- `attrRef(table, ref, Class)` luôn dùng converter `Map`, nên giá trị scalar trong `custom_values` có thể đọc lỗi.
- Test tự động mới chỉ có cho `DefaultDynamicInsert` và `DefaultDynamicUpdate`:
  - `./gradlew test`: unit test (SQL sinh ra, qua jOOQ `MockConnection`); integration test tự bỏ qua.
  - `MSM_TEST_DB_URL="jdbc:postgresql://localhost:5432/<db>?user=<user>" ./gradlew test`: chạy thêm `DynamicCommandsPostgresIT` trên PostgreSQL thật. Test **xóa và tạo lại** các bảng `product`, `tag`, `item`, `marker`, nên chỉ dùng database rỗng dành riêng cho test.
