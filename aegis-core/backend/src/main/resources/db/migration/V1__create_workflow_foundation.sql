create table workflows (
    id uuid primary key,
    status varchar(32) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    current_step varchar(255) not null,
    retry_count integer not null,
    request text not null
);

create table tasks (
    id uuid primary key,
    workflow_id uuid not null,
    type varchar(64) not null,
    status varchar(32) not null,
    payload text not null,
    retries integer not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_tasks_workflow foreign key (workflow_id) references workflows (id)
);

create table execution_logs (
    id uuid primary key,
    workflow_id uuid not null,
    task_id uuid,
    level varchar(16) not null,
    message text not null,
    created_at timestamp with time zone not null,
    constraint fk_execution_logs_workflow foreign key (workflow_id) references workflows (id),
    constraint fk_execution_logs_task foreign key (task_id) references tasks (id)
);

create index idx_tasks_workflow_id on tasks (workflow_id);
create index idx_execution_logs_workflow_id on execution_logs (workflow_id);
