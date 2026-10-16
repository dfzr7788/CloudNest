package com.cloudnest.user;

import com.baomidou.mybatisplus.annotation.*;

@TableName("sys_user")
public class Account {
  @TableId public String id;
  public String username;
  public String passwordHash;
  public String nickname;
  public String rootId;
  public Long storageQuota;
  public Long storageUsed;
  public Long storageReserved;
  public Long createdAt;
}
