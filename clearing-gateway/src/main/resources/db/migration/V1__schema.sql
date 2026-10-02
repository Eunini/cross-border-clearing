-- Participants (simulated banks). Amounts are integers in minor units of the
-- settlement currency unless a column says otherwise.
create table participant (
    id            integer primary key,
    bic           varchar(11) not null unique,
    name          varchar(140) not null,
    country       char(2)     not null,
    currency      char(3)     not null,
    net_debit_cap bigint      not null check (net_debit_cap >= 0),
    active        boolean     not null default true
);

-- Unsettled net position across open and closing cycles (positive = net receiver).
-- The pre-settlement risk check keeps position >= -net_debit_cap.
create table participant_position (
    participant_id integer primary key references participant (id),
    position       bigint  not null default 0,
    queued_count   integer not null default 0 check (queued_count >= 0),
    updated_at     timestamptz not null default now()
);

create table clearing_cycle (
    id                     bigserial primary key,
    business_date          date        not null,
    status                 varchar(16) not null check (status in ('OPEN', 'CLOSING', 'NETTED', 'SETTLED', 'FAILED')),
    opened_at              timestamptz not null default now(),
    closed_at              timestamptz,
    netted_at              timestamptz,
    settled_at             timestamptz,
    payment_count          integer,
    gross_value            bigint,
    net_value              bigint,
    transfer_count         integer,
    transfer_lower_bound   integer,
    transfers_optimal      boolean,
    plan_method            varchar(32),
    netting_micros         bigint,
    netting_round_trip_ms  bigint,
    liquidity_saving_pct   numeric(7, 2),
    transfer_reduction_pct numeric(7, 2),
    failure_reason         text
);
create unique index clearing_cycle_single_open on clearing_cycle (status) where status = 'OPEN';

-- Message-level idempotency: (instructing agent, MsgId) is unique.
create table inbound_message (
    id             bigserial primary key,
    instg_agent    varchar(11) not null,
    msg_id         varchar(35) not null,
    payload_sha256 char(64)    not null,
    nb_of_txs      integer     not null,
    received_at    timestamptz not null default now(),
    response_xml   text,
    unique (instg_agent, msg_id)
);

create table payment (
    id                    bigserial primary key,
    uetr                  uuid        not null unique,
    message_id            bigint references inbound_message (id),
    tx_sha256             char(64)    not null,
    end_to_end_id         varchar(35) not null,
    tx_id                 varchar(35),
    instr_id              varchar(35),
    debtor_agent          varchar(11) not null,
    creditor_agent        varchar(11) not null,
    debtor_participant    integer references participant (id),
    creditor_participant  integer references participant (id),
    debtor_name           varchar(140),
    debtor_account        varchar(34),
    creditor_name         varchar(140),
    creditor_account      varchar(34),
    currency              char(3)     not null,
    amount                bigint      not null,
    target_currency       char(3),
    target_amount         bigint,
    settlement_amount     bigint,
    fx_quote_id           uuid,
    fx_source_rate        numeric(20, 8),
    fx_target_rate        numeric(20, 8),
    quote_expires_at      timestamptz,
    state                 varchar(16) not null,
    reason_code           varchar(4),
    reason_text           varchar(105),
    cycle_id              bigint references clearing_cycle (id),
    received_at           timestamptz not null default now(),
    queued_at             timestamptz,
    accepted_at           timestamptz,
    updated_at            timestamptz not null default now()
);
create index payment_cycle_state on payment (cycle_id, state);
create index payment_queue on payment (queued_at, id) where state = 'QUEUED';
create index payment_accepted_at on payment (accepted_at) where accepted_at is not null;

-- EndToEndId duplicate detection per debtor agent (only for payments that passed validation).
create table end_to_end_ref (
    debtor_agent  varchar(11) not null,
    end_to_end_id varchar(35) not null,
    uetr          uuid        not null,
    primary key (debtor_agent, end_to_end_id)
);

-- Tracker history (gpi-style): one row per state transition.
create table payment_event (
    id          bigserial primary key,
    payment_id  bigint      not null references payment (id),
    from_state  varchar(16),
    to_state    varchar(16) not null,
    reason_code varchar(4),
    detail      varchar(200),
    occurred_at timestamptz not null default clock_timestamp()
);
create index payment_event_payment on payment_event (payment_id, id);

-- Transactional outbox.
create table outbox_event (
    id             bigserial primary key,
    aggregate_type varchar(32) not null,
    aggregate_id   varchar(64) not null,
    event_type     varchar(48) not null,
    recipient      varchar(11),
    payload        jsonb       not null,
    created_at     timestamptz not null default now(),
    published_at   timestamptz
);
create index outbox_unpublished on outbox_event (id) where published_at is null;

-- Simulated participant inbox: the outbox publisher's sink. event_id makes delivery idempotent.
create table participant_notification (
    event_id     bigint primary key,
    recipient    varchar(11) not null,
    event_type   varchar(48) not null,
    payload      jsonb       not null,
    delivered_at timestamptz not null default now()
);
create index participant_notification_recipient on participant_notification (recipient, event_id);

-- Netting results.
create table cycle_position (
    cycle_id       bigint  not null references clearing_cycle (id),
    participant_id integer not null references participant (id),
    gross_debit    bigint  not null,
    gross_credit   bigint  not null,
    net            bigint  not null,
    primary key (cycle_id, participant_id)
);

create table cycle_currency_position (
    cycle_id       bigint  not null references clearing_cycle (id),
    currency       char(3) not null,
    participant_id integer not null references participant (id),
    gross_debit    bigint  not null,
    gross_credit   bigint  not null,
    net            bigint  not null,
    primary key (cycle_id, currency, participant_id)
);

create table settlement_instruction (
    id           bigserial primary key,
    cycle_id     bigint      not null references clearing_cycle (id),
    from_participant integer not null references participant (id),
    to_participant   integer not null references participant (id),
    amount       bigint      not null check (amount > 0),
    status       varchar(16) not null check (status in ('PENDING', 'CONFIRMED')),
    confirmed_at timestamptz
);
create index settlement_instruction_cycle on settlement_instruction (cycle_id);

create table lsm_run (
    id                     bigserial primary key,
    ran_at                 timestamptz not null default now(),
    queued                 integer not null,
    released               integer not null,
    released_value         bigint  not null,
    released_by_offsetting integer not null,
    compute_micros         bigint  not null
);
