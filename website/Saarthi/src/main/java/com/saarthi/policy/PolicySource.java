package com.saarthi.policy;

import java.util.List;

public interface PolicySource {

    String getSourceName();

    List<PolicySourceItem> fetch();
}
